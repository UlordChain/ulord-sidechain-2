/*
 * This file is part of USC
 * Copyright (C) 2016 - 2018 USC developer team.
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

package co.usc.net;

import co.usc.BpListManager.BlmTransaction;
import co.usc.config.UscSystemProperties;
import co.usc.core.bc.BlockChainStatus;
import co.usc.crypto.Keccak256;
import co.usc.net.messages.*;
import co.usc.rpc.uos.UOSRpcChannel;
import co.usc.scoring.EventType;
import co.usc.scoring.PeerScoringManager;
import co.usc.validators.BlockValidationRule;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.core.Block;
import org.ethereum.core.BlockIdentifier;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.HashUtil;
import org.ethereum.net.server.ChannelManager;
import org.ethereum.util.Utils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class NodeMessageHandler implements MessageHandler, Runnable {
    private static final Logger logger = LoggerFactory.getLogger("messagehandler");
    private static final Logger loggerMessageProcess = LoggerFactory.getLogger("messageProcess");
    public static final int MAX_NUMBER_OF_MESSAGES_CACHED = 5000;
    public static final long RECEIVED_MESSAGES_CACHE_DURATION = TimeUnit.MINUTES.toMillis(2);

    private final UscSystemProperties config;
    private final BlockProcessor blockProcessor;
    private final SyncProcessor syncProcessor;
    private final ChannelManager channelManager;
    private final TransactionGateway transactionGateway;
    private final PeerScoringManager peerScoringManager;
    private volatile long lastStatusSent = System.currentTimeMillis();
    private volatile long lastTickSent = System.currentTimeMillis();

    private BlockValidationRule blockValidationRule;

    private LinkedBlockingQueue<MessageTask> queue = new LinkedBlockingQueue<>();
    private Set<Keccak256> receivedMessages = Collections.synchronizedSet(new HashSet<Keccak256>());
    private long cleanMsgTimestamp = 0;

    private volatile boolean stopped;

    private final UOSRpcChannel uosRpcChannel;

    @Autowired
    public NodeMessageHandler(UscSystemProperties config,
                              @Nonnull final BlockProcessor blockProcessor,
                              final SyncProcessor syncProcessor,
                              @Nullable final ChannelManager channelManager,
                              @Nullable final TransactionGateway transactionGateway,
                              @Nullable final PeerScoringManager peerScoringManager,
                              @Nonnull BlockValidationRule blockValidationRule,
                              UOSRpcChannel uosRpcChannel) {
        this.config = config;
        this.channelManager = channelManager;
        this.blockProcessor = blockProcessor;
        this.syncProcessor = syncProcessor;
        this.transactionGateway = transactionGateway;
        this.blockValidationRule = blockValidationRule;
        this.cleanMsgTimestamp = System.currentTimeMillis();
        this.peerScoringManager = peerScoringManager;
        this.uosRpcChannel = uosRpcChannel;
    }

    /**
     * processMessage processes a USC Message, doing the appropriate action based on the message type.
     *
     * @param sender  the message sender.
     * @param message the message to be processed.
     */
    public synchronized void processMessage(final MessageChannel sender, @Nonnull final Message message) {
        long start = System.nanoTime();
        logger.trace("Process message type: {}", message.getMessageType());

        MessageType mType = message.getMessageType();

        if (mType == MessageType.GET_BLOCK_MESSAGE) {
            this.processGetBlockMessage(sender, (GetBlockMessage) message);
        } else if (mType == MessageType.BLOCK_MESSAGE) {
            this.processBlockMessage(sender, (BlockMessage) message);
        } else if (mType == MessageType.STATUS_MESSAGE) {
            this.processStatusMessage(sender, (StatusMessage) message);
        } else if (mType == MessageType.BLOCK_REQUEST_MESSAGE) {
            this.processBlockRequestMessage(sender, (BlockRequestMessage) message);
        } else if (mType == MessageType.BLOCK_RESPONSE_MESSAGE) {
            this.processBlockResponseMessage(sender, (BlockResponseMessage) message);
        } else if (mType == MessageType.BODY_REQUEST_MESSAGE) {
            this.processBodyRequestMessage(sender, (BodyRequestMessage) message);
        } else if (mType == MessageType.BODY_RESPONSE_MESSAGE) {
            this.processBodyResponseMessage(sender, (BodyResponseMessage) message);
        } else if (mType == MessageType.BLOCK_HEADERS_REQUEST_MESSAGE) {
            this.processBlockHeadersRequestMessage(sender, (BlockHeadersRequestMessage) message);
        } else if (mType == MessageType.BLOCK_HEADERS_RESPONSE_MESSAGE) {
            this.processBlockHeadersResponseMessage(sender, (BlockHeadersResponseMessage) message);
        } else if (mType == MessageType.BLOCK_HASH_REQUEST_MESSAGE) {
            this.processBlockHashRequestMessage(sender, (BlockHashRequestMessage) message);
        } else if (mType == MessageType.BLOCK_HASH_RESPONSE_MESSAGE) {
            this.processBlockHashResponseMessage(sender, (BlockHashResponseMessage) message);
        } else if (mType == MessageType.SKELETON_REQUEST_MESSAGE) {
            this.processSkeletonRequestMessage(sender, (SkeletonRequestMessage) message);
        } else if (mType == MessageType.SKELETON_RESPONSE_MESSAGE) {
            this.processSkeletonResponseMessage(sender, (SkeletonResponseMessage) message);
        } else if (mType == MessageType.NEW_BLOCK_HASH_MESSAGE) {
            this.processNewBlockHashMessage(sender, (NewBlockHashMessage) message);
        } else if(!blockProcessor.hasBetterBlockToSync()) {
            if (mType == MessageType.NEW_BLOCK_HASHES) {
                this.processNewBlockHashesMessage(sender, (NewBlockHashesMessage) message);
            } else if (mType == MessageType.TRANSACTIONS) {
                this.processTransactionsMessage(sender, (TransactionsMessage) message);
            }
        } else {
            loggerMessageProcess.debug("Message[{}] not processed.", message.getMessageType());
        }

        loggerMessageProcess.debug("Message[{}] processed after [{}] nano.", message.getMessageType(), System.nanoTime() - start);
    }

    @Override
    public void postMessage(MessageChannel sender, Message message) throws InterruptedException {
        logger.trace("Start post message (queue size {}) (message type {})", this.queue.size(), message.getMessageType());
        // There's an obvious race condition here, but fear not.
        // receivedMessages and logger are thread-safe
        // cleanMsgTimestamp is a long replaced by the next value, we don't care
        // enough about the precision of the value it takes
        cleanExpiredMessages();
        tryAddMessage(sender, message);
        logger.trace("End post message (queue size {})", this.queue.size());
    }

    private void tryAddMessage(MessageChannel sender, Message message) {
        Keccak256 encodedMessage = new Keccak256(HashUtil.keccak256(message.getEncoded()));
        if (!receivedMessages.contains(encodedMessage)) {
            if (message.getMessageType() == MessageType.BLOCK_MESSAGE || message.getMessageType() == MessageType.TRANSACTIONS) {
                if (this.receivedMessages.size() >= MAX_NUMBER_OF_MESSAGES_CACHED) {
                    this.receivedMessages.clear();
                }
                this.receivedMessages.add(encodedMessage);
            }
            if (!this.queue.offer(new MessageTask(sender, message))){
                logger.trace("Queue full, message not added to the queue");
            }
        } else {
            recordEvent(sender, EventType.REPEATED_MESSAGE);
            logger.trace("Received message already known, not added to the queue");
        }
    }

    private void cleanExpiredMessages() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - cleanMsgTimestamp > RECEIVED_MESSAGES_CACHE_DURATION) {
            logger.trace("Cleaning {} messages from rlp queue", receivedMessages.size());
            receivedMessages.clear();
            cleanMsgTimestamp = currentTime;
        }
    }

    @Override
    public void start() {
        new Thread(this).start();
    }

    @Override
    public void stop() {
        this.stopped = true;
    }

    @Override
    public long getMessageQueueSize() {
        return this.queue.size();
    }

    @Override
    public void run() {
        while (!stopped) {
            MessageTask task = null;
            try {
                logger.trace("Get task");

                task = this.queue.poll(1, TimeUnit.SECONDS);

                loggerMessageProcess.debug("Queued Messages: {}", this.queue.size());

                if (task != null) {
                    logger.trace("Start task");
                    this.processMessage(task.getSender(), task.getMessage());
                    logger.trace("End task");
                } else {
                    logger.trace("No task");
                }

                updateTimedEvents();
            }
            catch (Exception ex) {
                logger.error("Unexpected error processing: {}", task, ex);
            }
        }
    }

    private void updateTimedEvents() {
        Long now = System.currentTimeMillis();
        Duration timeTick = Duration.ofMillis(now - lastTickSent);
        // TODO(lsebrie): handle timeouts properly
        lastTickSent = now;
        if (queue.isEmpty()){
            this.syncProcessor.onTimePassed(timeTick);
        }

        //Refresh status to peers every 10 seconds or so
        Duration timeStatus = Duration.ofMillis(now - lastStatusSent);
        if (timeStatus.getSeconds() > 10) {
            sendStatusToAll();
            lastStatusSent = now;
        }
    }

    private synchronized void sendStatusToAll() {
        BlockChainStatus blockChainStatus = this.blockProcessor.getBlockchain().getStatus();
        Block block = blockChainStatus.getBestBlock();

        Status status = new Status(block.getNumber(), block.getHash().getBytes(), block.getParentHash().getBytes());
        logger.trace("Sending status best block to all {} {}", status.getBestBlockNumber(), Hex.toHexString(status.getBestBlockHash()).substring(0, 8));
        this.channelManager.broadcastStatus(status);
    }

    public synchronized Block getBestBlock() {
        return this.blockProcessor.getBlockchain().getBestBlock();
    }

    /**
     * isValidBlock validates if the given block meets the minimum criteria to be processed:
     * The block should be valid and the block can't be too far in the future.
     *
     * @param block the block to check
     * @return true if the block is valid, false otherwise.
     */
    private boolean isValidBlock(@Nonnull final Block block) {
        try {
            // Validate BpList
            if(!syncProcessor.isPeerSyncing()) {
                if(!isValidBpList(block)) {
                    logger.error("Invalid BpList Block {}: {}", block.getNumber(), block.getShortHash());
                    return false;
                }
            }

            return blockValidationRule.isValid(block);
        } catch (Exception e) {
            logger.error("Failed to validate block {} {}: {}", block.getNumber(), block.getShortHash(), e);
            return false;
        }
    }

    /**
     * processBlockMessage processes a BlockMessage message, adding the block to the blockchain if appropriate, or
     * forwarding it to peers that are missing the Block.
     *
     * @param sender  the message sender.
     * @param message the BlockMessage.
     */
    private void processBlockMessage(@Nonnull final MessageChannel sender, @Nonnull final BlockMessage message) {
        final Block block = message.getBlock();

        logger.trace("Process block {} {}", block.getNumber(), block.getShortHash());

        if (block.isGenesis()) {
            logger.trace("Skip block processing {} {}", block.getNumber(), block.getShortHash());
            return;
        }

        long blockNumber = block.getNumber();

        if (this.blockProcessor.isAdvancedBlock(blockNumber)) {
            logger.trace("Too advanced block {} {}", blockNumber, block.getShortHash());
            return;
        }

        Metrics.processBlockMessage("start", block, sender.getPeerNodeID());

        if (!isValidBlock(block)) {
            logger.trace("Invalid block {} {}", blockNumber, block.getShortHash());
            recordEvent(sender, EventType.INVALID_BLOCK);
            return;
        }

        if (blockProcessor.hasBlockInSomeBlockchain(block.getHash().getBytes())){
            logger.trace("Block ignored: it's included in blockchain {} {}", blockNumber, block.getShortHash());
            Metrics.processBlockMessage("blockIgnored", block, sender.getPeerNodeID());
            return;
        }

        BlockProcessResult result = this.blockProcessor.processBlock(sender, block);
        Metrics.processBlockMessage("blockProcessed", block, sender.getPeerNodeID());
        tryRelayBlock(sender, block, result);
        recordEvent(sender, EventType.VALID_BLOCK);
        Metrics.processBlockMessage("finish", block, sender.getPeerNodeID());
    }

    private boolean isValidBpList(Block block) {
        List<String> producersList = block.getBpList();

        if(!Utils.isBp(producersList, config))
            return true;

        JSONArray bpList = uosRpcChannel.getBPList();
        List<String> producers = new ArrayList<>();
        for (int i = 0; i < bpList.length(); ++i) {
            JSONObject jsonObject = bpList.getJSONObject(i);
            String uosPubKey = jsonObject.getString("ulord_addr");
            producers.add(Utils.UosPubKeyToUlord(uosPubKey));
        }
        return producersList.equals(producers);
    }

    private void tryRelayBlock(@Nonnull MessageChannel sender, Block block, BlockProcessResult result) {
        // is new block and it is not orphan, it is in some blockchain
        if (result.wasBlockAdded(block) && !this.blockProcessor.hasBetterBlockToSync()) {
            relayBlock(sender, block);
        }
    }

    private void relayBlock(@Nonnull MessageChannel sender, Block block) {
        byte[] blockHash = block.getHash().getBytes();
        final BlockNodeInformation nodeInformation = this.blockProcessor.getNodeInformation();
        final Set<NodeID> nodesWithBlock = nodeInformation.getNodesByBlock(blockHash);
        final Set<NodeID> newNodes = this.syncProcessor.getKnownPeersNodeIDs().stream()
                .filter(p -> !nodesWithBlock.contains(p))
                .collect(Collectors.toSet());


        List<BlockIdentifier> identifiers = new ArrayList<>();
        identifiers.add(new BlockIdentifier(blockHash, block.getNumber()));
        channelManager.broadcastBlockHash(identifiers, newNodes);

        Metrics.processBlockMessage("blockRelayed", block, sender.getPeerNodeID());
    }

    private void processStatusMessage(@Nonnull final MessageChannel sender, @Nonnull final StatusMessage message) {
        final Status status = message.getStatus();
        logger.trace("Process status {}", status.getBestBlockNumber());
        this.syncProcessor.processStatus(sender, status);
    }

    private void processGetBlockMessage(@Nonnull final MessageChannel sender, @Nonnull final GetBlockMessage message) {
        final byte[] hash = message.getBlockHash();
        this.blockProcessor.processGetBlock(sender, hash);
    }

    private void processBlockRequestMessage(@Nonnull final MessageChannel sender, @Nonnull final BlockRequestMessage message) {
        final long requestId = message.getId();
        final byte[] hash = message.getBlockHash();
        this.blockProcessor.processBlockRequest(sender, requestId, hash);
    }

    private void processBlockResponseMessage(@Nonnull final MessageChannel sender, @Nonnull final BlockResponseMessage message) {
        this.syncProcessor.processBlockResponse(sender, message);
    }

    private void processSkeletonRequestMessage(@Nonnull final MessageChannel sender, @Nonnull final SkeletonRequestMessage message) {
        final long requestId = message.getId();
        final long startNumber = message.getStartNumber();
        this.blockProcessor.processSkeletonRequest(sender, requestId, startNumber);
    }

    private void processBlockHeadersRequestMessage(@Nonnull final MessageChannel sender, @Nonnull final BlockHeadersRequestMessage message) {
        final long requestId = message.getId();
        final byte[] hash = message.getHash();
        final int count = message.getCount();
        this.blockProcessor.processBlockHeadersRequest(sender, requestId, hash, count);
    }

    private void processBlockHashRequestMessage(@Nonnull final MessageChannel sender, @Nonnull final BlockHashRequestMessage message) {
        this.blockProcessor.processBlockHashRequest(sender, message.getId(), message.getHeight());
    }

    private void processBlockHashResponseMessage(@Nonnull final MessageChannel sender, @Nonnull final BlockHashResponseMessage message) {
        this.syncProcessor.processBlockHashResponse(sender, message);
    }

    private void processNewBlockHashMessage(@Nonnull final MessageChannel sender, @Nonnull final NewBlockHashMessage message) {
        this.syncProcessor.processNewBlockHash(sender, message);
    }

    private void processSkeletonResponseMessage(@Nonnull final MessageChannel sender, @Nonnull final SkeletonResponseMessage message) {
        this.syncProcessor.processSkeletonResponse(sender, message);
    }

    private void processBlockHeadersResponseMessage(@Nonnull final MessageChannel sender, @Nonnull final BlockHeadersResponseMessage message) {
        this.syncProcessor.processBlockHeadersResponse(sender, message);
    }

    private void processBodyRequestMessage(@Nonnull final MessageChannel sender, @Nonnull final BodyRequestMessage message) {
        final long requestId = message.getId();
        final byte[] hash = message.getBlockHash();
        this.blockProcessor.processBodyRequest(sender, requestId, hash);
    }

    private void processBodyResponseMessage(@Nonnull final MessageChannel sender, @Nonnull final BodyResponseMessage message) {
        this.syncProcessor.processBodyResponse(sender, message);
    }

    private void processNewBlockHashesMessage(@Nonnull final MessageChannel sender, @Nonnull final NewBlockHashesMessage message) {
        message.getBlockIdentifiers().forEach(bi -> Metrics.newBlockHash(bi, sender.getPeerNodeID()));
        blockProcessor.processNewBlockHashesMessage(sender, message);
    }

    private void processTransactionsMessage(@Nonnull final MessageChannel sender, @Nonnull final TransactionsMessage message) {
        long start = System.nanoTime();
        loggerMessageProcess.debug("Tx message about to be process: {}", message.getMessageContentInfo());

        List<Transaction> messageTxs = message.getTransactions();
        Metrics.processTxsMessage("start", messageTxs, sender.getPeerNodeID());

        List<Transaction> txs = new LinkedList();

        for (Transaction tx : messageTxs) {
            if (!tx.acceptTransactionSignature(config.getBlockchainConfig().getCommonConstants().getChainId())) {
                recordEvent(sender, EventType.INVALID_TRANSACTION);
            } else {
                txs.add(tx);
                recordEvent(sender, EventType.VALID_TRANSACTION);
            }
        }

        List<Transaction> acceptedTxs = transactionGateway.receiveTransactionsFrom(txs, sender.getPeerNodeID());

        Metrics.processTxsMessage("validTxsAddedToTransactionPool", acceptedTxs, sender.getPeerNodeID());

        Metrics.processTxsMessage("finish", acceptedTxs, sender.getPeerNodeID());

        loggerMessageProcess.debug("Tx message process finished after [{}] nano.", System.nanoTime() - start);
    }

    private void recordEvent(MessageChannel sender, EventType event) {
        if (sender == null) {
            return;
        }

        this.peerScoringManager.recordEvent(sender.getPeerNodeID(), sender.getAddress(), event);
    }

    @VisibleForTesting
    public BlockProcessor getBlockProcessor() {
        return blockProcessor;
    }

    private static class MessageTask {
        private MessageChannel sender;
        private Message message;

        public MessageTask(MessageChannel sender, Message message) {
            this.sender = sender;
            this.message = message;
        }

        public MessageChannel getSender() {
            return this.sender;
        }

        public Message getMessage() {
            return this.message;
        }

        @Override
        public String toString() {
            return "MessageTask{" +
                    "sender=" + sender +
                    ", message=" + message +
                    '}';
        }
    }
}

