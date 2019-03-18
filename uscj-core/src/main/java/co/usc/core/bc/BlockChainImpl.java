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

package co.usc.core.bc;

import co.usc.BpListManager.BlmTransaction;
import co.usc.blocks.BlockRecorder;
import co.usc.config.UscSystemProperties;
import co.usc.net.Metrics;
import co.usc.panic.PanicProcessor;
import co.usc.trie.Trie;
import co.usc.trie.TrieImpl;
import co.usc.ulordj.core.UldECKey;
import co.usc.validators.BlockValidator;
import com.google.common.annotations.VisibleForTesting;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.Constants;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.HashUtil;
import org.ethereum.db.BlockInformation;
import org.ethereum.db.BlockStore;
import org.ethereum.db.ReceiptStore;
import org.ethereum.db.TransactionInfo;
import org.ethereum.listener.EthereumListener;
import org.ethereum.util.RLP;
import org.ethereum.util.Utils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by ajlopez on 29/07/2016.
 */

/**
 * Original comment:
 *
 * The Ethereum blockchain is in many ways similar to the Ulord blockchain,
 * although it does have some differences.
 * <p>
 * The main difference between Ethereum and Ulord with regard to the blockchain architecture
 * is that, unlike Ulord, Ethereum blocks contain a copy of both the transaction list
 * and the most recent state. Aside from that, two other values, the block number and
 * the difficulty, are also stored in the block.
 * </p>
 * The block validation algorithm in Ethereum is as follows:
 * <ol>
 * <li>Check if the previous block referenced exists and is valid.</li>
 * <li>Check that the timestamp of the block is greater than that of the referenced previous block and less than 15 minutes into the future</li>
 * <li>Check that the block number, difficulty, transaction root, uncle root and gas limit (various low-level Ethereum-specific concepts) are valid.</li>
 * <li>Check that the proof of work on the block is valid.</li>
 * <li>Let S[0] be the STATE_ROOT of the previous block.</li>
 * <li>Let TX be the block's transaction list, with n transactions.
 * For all in in 0...n-1, set S[i+1] = APPLY(S[i],TX[i]).
 * If any applications returns an error, or if the total gas consumed in the block
 * up until this point exceeds the GASLIMIT, return an error.</li>
 * <li>Let S_FINAL be S[n], but adding the block reward paid to the blockProducer.</li>
 * <li>Check if S_FINAL is the same as the STATE_ROOT. If it is, the block is valid; otherwise, it is not valid.</li>
 * </ol>
 * See <a href="https://github.com/ethereum/wiki/wiki/White-Paper#blockchain-and-mining">Ethereum Whitepaper</a>
 *
 */

public class BlockChainImpl implements Blockchain {
    private static final Logger logger = LoggerFactory.getLogger("blockchain");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private final Repository repository;
    private final BlockStore blockStore;
    private final ReceiptStore receiptStore;
    private final TransactionPool transactionPool;
    private EthereumListener listener;
    private BlockValidator blockValidator;

    private final UscSystemProperties config;

    private volatile BlockChainStatus status = new BlockChainStatus(null);

    private final Object connectLock = new Object();
    private final Object accessLock = new Object();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final boolean flushEnabled;
//    private final int flushNumberOfBlocks;
    private final BlockExecutor blockExecutor;
    private BlockRecorder blockRecorder;
    private boolean noValidation;

    private long previousBlockTime;

    public BlockChainImpl(Repository repository,
                          BlockStore blockStore,
                          ReceiptStore receiptStore,
                          TransactionPool transactionPool,
                          EthereumListener listener,
                          BlockValidator blockValidator,
                          BlockExecutor blockExecutor,
                          UscSystemProperties config) {
        this.repository = repository;
        this.blockStore = blockStore;
        this.receiptStore = receiptStore;
        this.listener = listener;
        this.blockValidator = blockValidator;
        this.flushEnabled = config.isFlushEnabled();
//        this.flushNumberOfBlocks = config.flushNumberOfBlocks();
        this.blockExecutor = blockExecutor;
        this.transactionPool = transactionPool;
        this.config = config;
    }

    @Override
    public Repository getRepository() {
        return repository;
    }

    @Override
    public BlockStore getBlockStore() { return blockStore; }

    public EthereumListener getListener() { return listener; }

    public void setListener(EthereumListener listener) { this.listener = listener; }

    public BlockValidator getBlockValidator() { return blockValidator; }

    @VisibleForTesting
    public void setBlockValidator(BlockValidator validator) {
        this.blockValidator = validator;
    }

    @Override
    public long getSize() {
        return status.getBestBlock().getNumber() + 1;
    }

    /**
     * Try to add a block to a blockchain
     *
     * @param block        A block to try to add
     * @return IMPORTED_BEST if the block is the new best block
     *      IMPORTED_NOT_BEST if it was added to alternative chain
     *      NO_PARENT  the block parent is unknown yet
     *      INVALID_BLOCK   the block has invalida data/state
     *      EXISTS  the block was already processed
     */
    @Override
    public ImportResult tryToConnect(Block block) {
        this.lock.readLock().lock();

        try {
            if (block == null) {
                return ImportResult.INVALID_BLOCK;
            }

            if (!block.isSealed()) {
                panicProcessor.panic("unsealedblock", String.format("Unsealed block %s %s", block.getNumber(), block.getHash()));
                block.seal();
            }

            if (blockRecorder != null) {
                blockRecorder.writeBlock(block);
            }

            try {
                logger.trace("Try connect block hash: {}, number: {}",
                             block.getShortHash(),
                             block.getNumber());

                synchronized (connectLock) {
                    logger.trace("Start try connect");
                    long saveTime = System.nanoTime();
                    ImportResult result = internalTryToConnect(block);
                    long totalTime = System.nanoTime() - saveTime;
                    logger.info("block-n:[{}] hash:[{}], bp:{}, proc_time:[{}]nano, res:{}",
                            block.getNumber(), block.getShortHash(), Hex.toHexString(block.getCoinbase().getBytes()), totalTime, result);
                    return result;
                }
            } catch (Throwable t) {
                logger.error("Unexpected error: ", t);
                return ImportResult.INVALID_BLOCK;
            }
        }
        finally {
            this.lock.readLock().unlock();
        }
    }

    private void updateLatestIrreversibleBlock(Block block) {
        List<Long> blockNumbers = new ArrayList<>();
        List<String> bpList = block.getBpList();

        for (String bp : bpList) {
            long lastKnownBlockNum = 0;

            Block startBlock = block;
            for(int i = 0; i < bpList.size() * 2; ++i) {

                if(startBlock == null || startBlock.isGenesis()) {
                    break;
                }

                String parentBp = startBlock.getCoinbase().toString();
                String bpAddr = Hex.toHexString(ECKey.fromPublicOnly(Hex.decode(bp)).getAddress());
                if(parentBp.equals(bpAddr)) {
                    lastKnownBlockNum = startBlock.getNumber();
                    break;
                }
                startBlock = blockStore.getBlockByHash(startBlock.getParentHash().getBytes());
            }
            blockNumbers.add(lastKnownBlockNum);
        }

        Collections.sort(blockNumbers);

        Long confirmedBlockNum = blockNumbers.get((blockNumbers.size() - 1) / 3);

        markBlocksAsIrreversible(confirmedBlockNum);
    }

    private void markBlocksAsIrreversible(Long confirmedBlockNum) {
        Block block = blockStore.getChainBlockByNumber(confirmedBlockNum);

        if(block == null || block.isGenesis()) return;

        if(!block.isIrreversible()) {
            logger.info("Block " + confirmedBlockNum + " marked irreversible.");
            block.setIrreversible();
            blockStore.updateBlockIrreversible(block);
            // Check previous blocks
            markBlocksAsIrreversible(--confirmedBlockNum);
        }
    }

    @Override
    public void suspendProcess() {
        this.lock.writeLock().lock();
    }

    @Override
    public void resumeProcess() {
        this.lock.writeLock().unlock();
    }

    private ImportResult internalTryToConnect(Block block) {
        if (blockStore.getBlockByHash(block.getHash().getBytes()) != null) {
            logger.debug("Block already exist in chain hash: {}, number: {}",
                         block.getShortHash(),
                         block.getNumber());

            return ImportResult.EXIST;
        }

        Block siblingBlock = blockStore.getChainBlockByNumber(block.getNumber());
        if (siblingBlock != null) {
            if(siblingBlock.isIrreversible()) {
                logger.warn("Sibling block is irreversible, failed to add block:{}, hash:{}", block.getNumber(), block.getShortHash());
                return ImportResult.INVALID_BLOCK;
            } else {
                if(siblingBlock.getCoinbase().equals(block.getCoinbase())) {
                    long timeGap = siblingBlock.getTimestamp() - block.getTimestamp();
                    if(timeGap < (Constants.getBlockIntervalMs() * Constants.getProducerRepetitions())/1000) {
                        logger.warn("Block is of the same round, failed to add block:{}, hash:{}", block.getNumber(), block.getShortHash());
                        return ImportResult.INVALID_BLOCK;
                    }
                }
            }
        }

        Block bestBlock;

        // get current state
        synchronized (accessLock) {
            bestBlock = status.getBestBlock();
        }

        Block parent;

        // Incoming block is child of current best block
        if (bestBlock == null || bestBlock.isParentOf(block)) {
            parent = bestBlock;
        }
        // else, Get parent
        else {
            parent = blockStore.getBlockByHash(block.getParentHash().getBytes());
            if (parent == null) {
                return ImportResult.NO_PARENT;
            }
        }

        // Validate incoming block before its processing
        if (!isValid(block)) {
            long blockNumber = block.getNumber();
            logger.warn("Invalid block with number: {}", blockNumber);
            panicProcessor.panic("invalidblock", String.format("Invalid block %s %s", blockNumber, block.getHash()));
            return ImportResult.INVALID_BLOCK;
        }

        BlockResult result = null;

        if (parent != null) {
            if (this.noValidation) {
                result = blockExecutor.executeAll(block, parent.getStateRoot());
            } else {
                result = blockExecutor.execute(block, parent.getStateRoot(), false);
            }

            boolean isValid = noValidation || blockExecutor.validate(block, result);

            if (!isValid) {
                return ImportResult.INVALID_BLOCK;
            }
        }

        //TODO this need refactor to add the new block
        // It is the new best block

        if (bestBlock != null && !bestBlock.isParentOf(block)) {
            logger.info("Rebranching: {} ~> {} From block {} ~> {}",
                    bestBlock.getShortHash(), block.getShortHash(), bestBlock.getNumber(), block.getNumber());
            BlockFork fork = new BlockFork();
            fork.calculate(bestBlock, block, blockStore);
            Metrics.rebranch(bestBlock, block, fork.getNewBlocks().size() + fork.getOldBlocks().size());
            blockStore.reBranch(block);
        }

        switchToBlockChain(block);
        saveReceipts(block, result);
        processBest(block);
        onBestBlock(block, result);
        onBlock(block, result);
        updateLatestIrreversibleBlock(block);

        long latestBlockTime = Instant.now().toEpochMilli() / 1000;
        if(latestBlockTime - previousBlockTime > (Constants.getProducerRepetitions() - 1))  {
            previousBlockTime = latestBlockTime;
            flushData();
        }

        if (block.getNumber() % 100 == 0) {
            logger.info("*** Last block added [ #{} ]", block.getNumber());
        }

        return ImportResult.IMPORTED_BEST;
    }

    @Override
    public BlockChainStatus getStatus() {
        return status;
    }

    /**
     * Change the blockchain status, to a new best block with difficulty
     *
     * @param block        The new best block
     */
    @Override
    public void setStatus(Block block) {
        synchronized (accessLock) {
            status = new BlockChainStatus(block);
            blockStore.saveBlock(block,true);
            repository.syncToRoot(block.getStateRoot());
        }
    }

    @Override
    public Block getBlockByHash(byte[] hash) {
        return blockStore.getBlockByHash(hash);
    }

    @Override
    public void setExitOn(long exitOn) {

    }

    @Override
    public boolean isBlockExist(byte[] hash) {
        return blockStore.isBlockExist(hash);
    }

    @Override
    public List<BlockHeader> getListOfHeadersStartFrom(BlockIdentifier identifier, int skip, int limit, boolean reverse) {
        return null;
    }

    @Override
    public List<byte[]> getListOfBodiesByHashes(List<byte[]> hashes) {
        return null;
    }

    @Override
    public List<Block> getBlocksByNumber(long number) {
        return blockStore.getChainBlocksByNumber(number);
    }

    @Override
    public List<BlockInformation> getBlocksInformationByNumber(long number) {
        synchronized (accessLock) {
            return this.blockStore.getBlocksInformationByNumber(number);
        }
    }

    @Override
    public boolean hasBlockInSomeBlockchain(@Nonnull final byte[] hash) {
        final Block block = this.getBlockByHash(hash);
        return block != null && this.blockIsInIndex(block);
    }

    /**
     * blockIsInIndex returns true if a given block is indexed in the blockchain (it might not be the in the
     * canonical branch).
     *
     * @param block the block to check for.
     * @return true if there is a block in the blockchain with that hash.
     */
    private boolean blockIsInIndex(@Nonnull final Block block) {
        final List<Block> blocks = this.getBlocksByNumber(block.getNumber());

        return blocks.stream().anyMatch(block::fastEquals);
    }

    @Override
    public void removeBlocksByNumber(long number) {
        this.lock.writeLock().lock();

        try {
            List<Block> blocks = this.getBlocksByNumber(number);

            for (Block block : blocks) {
                blockStore.removeBlock(block);
            }
        }
        finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public Block getBlockByNumber(long number) { return blockStore.getChainBlockByNumber(number); }

    @Override
    public void setBestBlock(Block block) {
        this.setStatus(block);
    }

    @Override
    public Block getBestBlock() {
        return this.status.getBestBlock();
    }

    public void setNoValidation(boolean noValidation) {
        this.noValidation = noValidation;
    }

    /**
     * Returns transaction info by hash
     *
     * @param hash      the hash of the transaction
     * @return transaction info, null if the transaction does not exist
     */
    @Override
    public TransactionInfo getTransactionInfo(byte[] hash) {
        TransactionInfo txInfo = receiptStore.get(hash);

        if (txInfo == null) {
            return null;
        }

        Transaction tx = this.getBlockByHash(txInfo.getBlockHash()).getTransactionsList().get(txInfo.getIndex());
        txInfo.setTransaction(tx);

        return txInfo;
    }

    @Override
    public void close() {

    }

    @Override @VisibleForTesting
    public byte[] getBestBlockHash() {
        return getBestBlock().getHash().getBytes();
    }

    @Override
    public void setBlockRecorder(BlockRecorder blockRecorder) {
        this.blockRecorder = blockRecorder;
    }

    private void switchToBlockChain(Block block) {
        synchronized (accessLock) {
            storeBlock(block, true);
            status = new BlockChainStatus(block);
            repository.syncToRoot(block.getStateRoot());
        }
    }

    private void storeBlock(Block block, boolean inBlockChain) {
        blockStore.saveBlock(block, inBlockChain);
        logger.trace("Block saved: number: {}, hash: {}",
                block.getNumber(), block.getShortHash());
    }

    private void saveReceipts(Block block, BlockResult result) {
        if (result == null) {
            return;
        }

        if (result.getTransactionReceipts().isEmpty()) {
            return;
        }

        receiptStore.saveMultiple(block.getHash().getBytes(), result.getTransactionReceipts());
    }

    private void processBest(final Block block) {
        EventDispatchThread.invokeLater(() -> transactionPool.processBest(block));
    }

    private void onBlock(Block block, BlockResult result) {
        if (result != null && listener != null) {
            listener.trace(String.format("Block chain size: [ %d ]", this.getSize()));
            listener.onBlock(block, result.getTransactionReceipts());
        }
    }

    private void onBestBlock(Block block, BlockResult result) {
        if (result != null && listener != null){
            listener.onBestBlock(block, result.getTransactionReceipts());
        }
    }

    private boolean isValid(Block block) {
        if (block.isGenesis()) {
            return true;
        }

        return blockValidator.isValid(block);
    }

    private void flushData() {
        long saveTime = System.nanoTime();
        repository.flush();
        long totalTime = System.nanoTime() - saveTime;
        logger.trace("repository flush: [{}]nano", totalTime);
        saveTime = System.nanoTime();
        blockStore.flush();
        totalTime = System.nanoTime() - saveTime;
        logger.trace("blockstore flush: [{}]nano", totalTime);
    }

    public static byte[] calcTxTrie(List<Transaction> transactions) {
        return Block.getTxTrie(transactions).getHash().getBytes();
    }

    public static byte[] calcReceiptsTrie(List<TransactionReceipt> receipts) {
        Trie receiptsTrie = new TrieImpl();

        if (receipts == null || receipts.isEmpty()) {
            return HashUtil.EMPTY_TRIE_HASH;
        }

        for (int i = 0; i < receipts.size(); i++) {
            receiptsTrie = receiptsTrie.put(RLP.encodeInt(i), receipts.get(i).getEncoded());
        }

        return receiptsTrie.getHash().getBytes();
    }
}
