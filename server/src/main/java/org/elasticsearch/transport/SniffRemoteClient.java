/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.client.support.AbstractClient;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport.Connection;
import org.elasticsearch.transport.TransportService.HandshakeResponse;

import io.crate.action.FutureActionListener;
import io.crate.common.collections.Lists2;
import io.crate.exceptions.Exceptions;
import io.crate.replication.logical.metadata.ConnectionInfo;

public final class SniffRemoteClient extends AbstractClient {

    private final TransportService transportService;
    private final ConnectionProfile profile;
    private final List<Supplier<DiscoveryNode>> seedNodes;
    private final String clusterAlias;
    private final SetOnce<ClusterName> remoteClusterName = new SetOnce<>();
    private final Predicate<ClusterName> allNodesShareClusterName;

    private CompletableFuture<DiscoveredNodes> discoveredNodes = null;

    public SniffRemoteClient(Settings nodeSettings,
                             ThreadPool threadPool,
                             ConnectionInfo connectionInfo,
                             String clusterAlias,
                             TransportService transportService) {
        super(nodeSettings, threadPool);
        this.clusterAlias = clusterAlias;
        this.transportService = transportService;
        this.seedNodes = Lists2.map(
            connectionInfo.hosts(),
            seedNode -> () -> resolveSeedNode(clusterAlias, seedNode)
        );
        this.profile = new ConnectionProfile.Builder()
            .setConnectTimeout(TransportSettings.CONNECT_TIMEOUT.get(settings))
            .setHandshakeTimeout(TransportSettings.CONNECT_TIMEOUT.get(settings))
            .setPingInterval(TransportSettings.PING_SCHEDULE.get(settings))
            .setCompressionEnabled(TransportSettings.TRANSPORT_COMPRESS.get(settings))
            .addConnections(1, TransportRequestOptions.Type.BULK)
            .addConnections(1, TransportRequestOptions.Type.PING)
            .addConnections(1, TransportRequestOptions.Type.STATE)
            .addConnections(1, TransportRequestOptions.Type.RECOVERY)
            .addConnections(1, TransportRequestOptions.Type.REG)
            .build();
        this.allNodesShareClusterName = new Predicate<ClusterName>() {

            @Override
            public boolean test(ClusterName c) {
                ClusterName clusterName = remoteClusterName.get();
                return clusterName == null || c.equals(clusterName);
            }
        };
    }

    @Override
    public void close() {
    }

    class DiscoveredNodes {

        private final HashMap<DiscoveryNode, CompletableFuture<Connection>> connections;

        DiscoveredNodes(Connection connection) {
            this.connections = new HashMap<>();
            this.connections.put(connection.getNode(), CompletableFuture.completedFuture(connection));
        }

        public CompletionStage<Connection> any() {
            synchronized (this) {
                var entry = connections.entrySet().iterator().next();
                var discoveryNode = entry.getKey();
                var conn = entry.getValue();
                // TODO: nodes could disappear forever, remove dead nodes and restart with seeds if there aren't any left
                if (conn.isCompletedExceptionally() || (conn.isDone() && conn.join().isClosed())) {
                    var newConn = connectWithHandshake(discoveryNode);
                    connections.replace(discoveryNode, newConn);
                    return newConn;
                }
                return conn;
            }
        }

        public CompletionStage<Connection> connect(DiscoveryNode preferredNode) {
            return any();
        }
    }

    public CompletableFuture<Connection> ensureConnected(@Nullable DiscoveryNode preferredNode) {
        synchronized (this) {
            if (discoveredNodes == null) {
                discoveredNodes = discoverNodes();
            } else if (discoveredNodes.isCompletedExceptionally()) {
                discoveredNodes = discoverNodes();
            }
            return discoveredNodes.thenCompose(nodes -> {
                if (preferredNode == null) {
                    return nodes.any();
                } else {
                    return nodes.connect(preferredNode);
                }
            });
        }
    }

    private CompletableFuture<DiscoveredNodes> discoverNodes() {
        return tryConnectToAnySeedNode(seedNodes.iterator())
            // TODO: actually discover nodes by retrieving the cluster state
            .thenApply(DiscoveredNodes::new);
    }

    private CompletableFuture<Connection> tryConnectToAnySeedNode(Iterator<Supplier<DiscoveryNode>> seedNodes) {
        if (seedNodes.hasNext()) {
            try {
                DiscoveryNode seedNode = seedNodes.next().get();
                return connectWithHandshake(seedNode)
                    .exceptionallyCompose(err -> {
                        if (seedNodes.hasNext()) {
                            return tryConnectToAnySeedNode(seedNodes);
                        } else {
                            throw Exceptions.toRuntimeException(err);
                        }
                    });
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }
        return CompletableFuture.failedFuture(new NoSuchRemoteClusterException(clusterAlias));
    }

    private CompletableFuture<Connection> connectWithHandshake(DiscoveryNode node) {
        FutureActionListener<Connection, Connection> openedConnection = FutureActionListener.newInstance();
        transportService.openConnection(node, profile, openedConnection);

        CompletableFuture<HandshakeResponse> receivedHandshakeResponse = openedConnection.thenCompose(connection -> {
            FutureActionListener<HandshakeResponse, HandshakeResponse> handshakeResponse = FutureActionListener.newInstance();
            transportService.handshake(
                connection,
                profile.getHandshakeTimeout().millis(),
                allNodesShareClusterName,
                handshakeResponse
            );
            return handshakeResponse;
        });

        return receivedHandshakeResponse.thenCompose(handshakeResponse -> {
            DiscoveryNode handshakeNode = handshakeResponse.getDiscoveryNode();

            // TODO: ensure node is compatible
            // if (nodeIsCompatible(handshakeNode)) {
            // }

            FutureActionListener<Void, Connection> connectedListener = new FutureActionListener<>(ignored -> openedConnection.join());
            transportService.connectToNode(handshakeNode, connectedListener);
            return connectedListener;
        });
    }

    @Override
    protected <Request extends TransportRequest, Response extends TransportResponse> void doExecute(
            ActionType<Response> action,
            Request request,
            ActionListener<Response> listener) {
        DiscoveryNode targetNode = request instanceof RemoteClusterAwareRequest remoteClusterAware
            ? remoteClusterAware.getPreferredTargetNode()
            : null;
        ensureConnected(targetNode).whenComplete((conn, err) -> {
            if (err == null) {
                var connection = targetNode == null ? conn : new ProxyConnection(conn, targetNode);
                transportService.sendRequest(
                    connection,
                    action.name(),
                    request,
                    TransportRequestOptions.EMPTY,
                    new ActionListenerResponseHandler<>(listener, action.getResponseReader())
                );
            } else {
                listener.onFailure(Exceptions.toException(err));
            }
        });
    }

    private static DiscoveryNode resolveSeedNode(String clusterAlias, String address) {
        TransportAddress transportAddress = new TransportAddress(RemoteConnectionParser.parseConfiguredAddress(address));
        return new DiscoveryNode(
            "sniff_to=" + clusterAlias + "#" + transportAddress.toString(),
            transportAddress,
            Version.CURRENT.minimumCompatibilityVersion()
        );
    }
}
