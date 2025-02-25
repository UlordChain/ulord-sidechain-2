/*
 * This file is part of USC
 * Copyright (C) 2016 - 2018 USC developer team.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.net.server;

import co.usc.config.UscSystemProperties;
import co.usc.net.Metrics;
import co.usc.net.NodeID;
import co.usc.net.Status;
import co.usc.net.eth.UscMessage;
import co.usc.net.messages.*;
import co.usc.scoring.InetAddressBlock;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.config.NodeFilter;
import org.ethereum.core.Block;
import org.ethereum.core.BlockIdentifier;
import org.ethereum.core.Transaction;
import org.ethereum.net.eth.message.EthMessage;
import org.ethereum.net.message.ReasonCode;
import org.ethereum.sync.SyncPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author Roman Mandeleil
 * @since 11.11.2014
 */
@Component("ChannelManager")
public class ChannelManagerImpl implements ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger("net");

    // If the inbound peer connection was dropped by us with a reason message
    // then we ban that peer IP on any connections for some time to protect from
    // too active peers
    private static final Duration INBOUND_CONNECTION_BAN_TIMEOUT = Duration.ofSeconds(10);
    private final Object activePeersLock = new Object();
    private final Map<NodeID, Channel> activePeers;

    // Using a concurrent list
    // (the add and remove methods copy an internal array,
    // but the iterator directly use the internal array)
    private final List<Channel> newPeers;

    private final ScheduledExecutorService mainWorker;
    private final Map<InetAddress, Instant> disconnectionsTimeouts;
    private final Object disconnectionTimeoutsLock = new Object();

    private final SyncPool syncPool;
    private final NodeFilter trustedPeers;
    private final int maxActivePeers;
    private final int maxConnectionsAllowed;
    private final int networkCIDR;

    @Autowired
    public ChannelManagerImpl(UscSystemProperties config, SyncPool syncPool) {
        this.mainWorker = Executors.newSingleThreadScheduledExecutor(target -> new Thread(target, "newPeersProcessor"));
        this.syncPool = syncPool;
        this.maxActivePeers = config.maxActivePeers();
        this.trustedPeers = config.trustedPeers();
        this.disconnectionsTimeouts = new HashMap<>();
        this.activePeers = new ConcurrentHashMap<>();
        this.newPeers = new CopyOnWriteArrayList<>();
        this.maxConnectionsAllowed = config.maxConnectionsAllowed();
        this.networkCIDR = config.networkCIDR();
    }

    @Override
    public void start() {
        mainWorker.scheduleWithFixedDelay(this::handleNewPeersAndDisconnections, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        mainWorker.shutdown();
    }

    private void handleNewPeersAndDisconnections(){
        this.tryProcessNewPeers();
        this.cleanDisconnections();
    }

    @VisibleForTesting
    public void tryProcessNewPeers() {
        if (newPeers.isEmpty()) {
            return;
        }

        try {
            processNewPeers();
        } catch (Exception e) {
            logger.error("Error", e);
        }
    }

    private void cleanDisconnections() {
        synchronized (disconnectionTimeoutsLock) {
            Instant now = Instant.now();
            disconnectionsTimeouts.values().removeIf(v -> !isRecent(v, now));
        }
    }

    private void processNewPeers() {
        List<Channel> processedChannels = new ArrayList<>();
        newPeers.stream().filter(Channel::isProtocolsInitialized).forEach(c -> processNewPeer(c, processedChannels));
        newPeers.removeAll(processedChannels);
    }

    private void processNewPeer(Channel channel, List<Channel> processedChannels) {
        ReasonCode reason = getNewPeerDisconnectionReason(channel);
        if (reason != null) {
            disconnect(channel, reason);
        } else {
            addToActives(channel);
        }
        processedChannels.add(channel);
    }

    private ReasonCode getNewPeerDisconnectionReason(Channel channel) {
        if (activePeers.containsKey(channel.getNodeId())) {
            return ReasonCode.DUPLICATE_PEER;
        }

        if (!channel.isActive() && activePeers.size() >= maxActivePeers && !trustedPeers.accept(channel.getNode())) {
            return ReasonCode.TOO_MANY_PEERS;
        }

        return null;
    }

    private void disconnect(Channel peer, ReasonCode reason) {
        logger.debug("Disconnecting peer with reason {} : {}", reason, peer);
        peer.disconnect(reason);
        synchronized (disconnectionTimeoutsLock){
            disconnectionsTimeouts.put(peer.getInetSocketAddress().getAddress(),
                                       Instant.now().plus(INBOUND_CONNECTION_BAN_TIMEOUT));
        }
    }

    public boolean isRecentlyDisconnected(InetAddress peerAddress) {
        synchronized (disconnectionTimeoutsLock) {
            return isRecent(disconnectionsTimeouts.getOrDefault(peerAddress, Instant.EPOCH), Instant.now());
        }
    }

    private boolean isRecent(Instant disconnectionTimeout, Instant currentTime) {
        return currentTime.isBefore(disconnectionTimeout);
    }

    private void addToActives(Channel peer) {
        if (peer.isUsingNewProtocol() || peer.hasEthStatusSucceeded()) {
            syncPool.add(peer);
            synchronized (activePeersLock){
                activePeers.put(peer.getNodeId(), peer);
            }
        }
    }

    /**
     * broadcastBlock Propagates a block message across active peers
     *
     * @param block new Block to be sent
     * @return a set containing the ids of the peers that received the block.
     */
    @Nonnull
    public Set<NodeID> broadcastBlock(@Nonnull final Block block) {
        Metrics.broadcastBlock(block);

        final Set<NodeID> nodesIdsBroadcastedTo = new HashSet<>();
        final BlockIdentifier bi = new BlockIdentifier(block.getHash().getBytes(), block.getNumber());
        final EthMessage newBlock = new UscMessage(new BlockMessage(block));
        final EthMessage newBlockHashes = new UscMessage(new NewBlockHashesMessage(Arrays.asList(bi)));
        synchronized (activePeersLock){
            // Get a randomized list with all the peers that don't have the block yet.
            activePeers.values().forEach(c -> logger.trace("USC activePeers: {}", c));
            List<Channel> peers = new ArrayList<>(activePeers.values());
            Collections.shuffle(peers);

            int sqrt = (int) Math.floor(Math.sqrt(peers.size()));
            for (int i = 0; i < sqrt; i++) {
                Channel peer = peers.get(i);
                nodesIdsBroadcastedTo.add(peer.getNodeId());
                logger.trace("USC propagate: {}", peer);
                peer.sendMessage(newBlock);
            }
            for (int i = sqrt; i < peers.size(); i++) {
                Channel peer = peers.get(i);
                logger.trace("USC announce: {}", peer);
                peer.sendMessage(newBlockHashes);
            }
        }

        return nodesIdsBroadcastedTo;
    }

    @Nonnull
    public Set<NodeID> broadcastBlockHash(@Nonnull final List<BlockIdentifier> identifiers, final Set<NodeID> targets) {
        final Set<NodeID> nodesIdsBroadcastedTo = new HashSet<>();
        final EthMessage newBlockHash = new UscMessage(new NewBlockHashesMessage(identifiers));

        synchronized (activePeersLock){
            activePeers.values().forEach(c -> logger.trace("USC activePeers: {}", c));

            activePeers.values().stream()
                    .filter(p -> targets.contains(p.getNodeId()))
                    .forEach(peer -> {
                        logger.trace("USC announce hash: {}", peer);
                        peer.sendMessage(newBlockHash);
                    });
        }

        return nodesIdsBroadcastedTo;
    }

    /**
     * broadcastTransaction Propagates a transaction message across active peers with exclusion of
     * the peers with an id belonging to the skip set.
     *
     * @param transaction new Transaction to be sent
     * @param skip  the set of peers to avoid sending the message.
     * @return a set containing the ids of the peers that received the transaction.
     */
    @Nonnull
    public Set<NodeID> broadcastTransaction(@Nonnull final Transaction transaction, final Set<NodeID> skip) {
        Metrics.broadcastTransaction(transaction);
        List<Transaction> transactions = Collections.singletonList(transaction);

        final Set<NodeID> nodesIdsBroadcastedTo = new HashSet<>();
        final EthMessage newTransactions = new UscMessage(new TransactionsMessage(transactions));

        activePeers.values().stream()
            .filter(p -> !skip.contains(p.getNodeId()))
            .forEach(peer -> {
                peer.sendMessage(newTransactions);
                nodesIdsBroadcastedTo.add(peer.getNodeId());
            });

        return nodesIdsBroadcastedTo;
    }

    @Override
    public int broadcastStatus(Status status) {
        final EthMessage message = new UscMessage(new StatusMessage(status));
        synchronized (activePeersLock){
            if (activePeers.isEmpty()) {
                return 0;
            }

            int numberOfPeersToSendStatusTo = getNumberOfPeersToSendStatusTo(activePeers.size());
            List<Channel> shuffledPeers = new ArrayList<>(activePeers.values());
            Collections.shuffle(shuffledPeers);
            shuffledPeers.stream()
                    .limit(numberOfPeersToSendStatusTo)
                    .forEach(c -> c.sendMessage(message));
            return numberOfPeersToSendStatusTo;
        }
    }

    @VisibleForTesting
    int getNumberOfPeersToSendStatusTo(int peerCount) {
        // Send to the sqrt of number of peers.
        // Make sure the number is between 3 and 10 (unless there are less than 3 peers).
        int peerCountSqrt = (int) Math.sqrt(peerCount);
        return Math.min(10, Math.min(Math.max(3, peerCountSqrt), peerCount));
    }

    public void add(Channel peer) {
        newPeers.add(peer);
    }

    public void notifyDisconnect(Channel channel) {
        logger.debug("Peer {}: notifies about disconnect", channel.getPeerIdShort());
        channel.onDisconnect();
        syncPool.onDisconnect(channel);
        synchronized (activePeersLock){
            activePeers.values().remove(channel);
        }
        if(newPeers.remove(channel)) {
            logger.info("Peer removed from active peers: {}", channel.getPeerId());
        }
    }

    public void onSyncDone(boolean done) {
        activePeers.values().forEach(channel -> channel.onSyncDone(done));
    }

    public Collection<Channel> getActivePeers() {
        // from the docs: it is imperative to synchronize when iterating
        synchronized (activePeersLock){
            return new ArrayList<>(activePeers.values());
        }
    }

    @Override
    public boolean sendMessageTo(NodeID nodeID, MessageWithId message) {
        Channel channel = activePeers.get(nodeID);
        if (channel == null){
            return false;
        }
        EthMessage msg = new UscMessage(message);
        channel.sendMessage(msg);
        return true;
    }

    public boolean isAddressBlockAvailable(InetAddress inetAddress) {
        synchronized (activePeersLock) {
            //TODO(lsebrie): save block address in a data structure and keep updated on each channel add/remove
            //TODO(lsebrie): check if we need to use a different networkCIDR for ipv6
            return activePeers.values().stream()
                    .map(ch -> new InetAddressBlock(ch.getInetSocketAddress().getAddress(), networkCIDR))
                    .filter(block -> block.contains(inetAddress))
                    .count() < maxConnectionsAllowed;
        }
    }

}
