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

package co.usc.bp;

import co.usc.ulordj.core.*;
import co.usc.config.MiningConfig;
import co.usc.config.UscSystemProperties;
import co.usc.core.Coin;
import co.usc.core.UscAddress;
import co.usc.crypto.Keccak256;
import co.usc.net.BlockProcessor;
import co.usc.panic.PanicProcessor;
import co.usc.ulordj.params.MainNetParams;
import co.usc.ulordj.params.TestNet3Params;
import com.google.common.annotations.VisibleForTesting;
import javafx.util.Pair;
import org.ethereum.core.*;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

import co.usc.rpc.uos.UOSRpcChannel;
/**
 * The MinerServer provides support to components that perform the actual mining.
 * It builds blocks to bp and publishes blocks once a valid nonce was found by the blockProducer.
 *
 * @author Oscar Guindzberg
 */

@Component("MinerServer")
public class MinerServerImpl implements MinerServer {
    private static final long DELAY_BETWEEN_REFRESH_BP_LIST_MS = TimeUnit.MILLISECONDS.toMillis(500);

    private static final Logger logger = LoggerFactory.getLogger("minerserver");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private static final int CACHE_SIZE = 20;

    private final Ethereum ethereum;
    private final Blockchain blockchain;
    private final BlockToSignBuilder builder;
    private Timer refreshBlockTimer;
    private Timer refreshBPListTimer;

    private NewBlockListener blockListener;

    private boolean started;
    private boolean isBP;
    private boolean isTest = true;
    private byte[] extraData;

    @GuardedBy("lock")
    private Keccak256 latestParentHash;
    @GuardedBy("lock")
    private Block latestBlock;
    @GuardedBy("lock")
    private Coin latestPaidFeesWithNotify;
    @GuardedBy("lock")
    private volatile MinerWork currentWork; // This variable can be read at anytime without the lock.
    private final Object lock = new Object();

    private final UscAddress coinbaseAddress;
    private final BigDecimal minFeesNotifyInDollars;
    private final BigDecimal gasUnitInDollars;

    private final BlockProcessor nodeBlockProcessor;

    private final UscSystemProperties config;

    private Pair<String, Long> bpSchedules;

    @Autowired
    public MinerServerImpl(
            UscSystemProperties config,
            Ethereum ethereum,
            Blockchain blockchain,
            BlockProcessor nodeBlockProcessor,
            BlockToSignBuilder builder,
            MiningConfig miningConfig) {
        this.config = config;
        this.ethereum = ethereum;
        this.blockchain = blockchain;
        this.nodeBlockProcessor = nodeBlockProcessor;
        this.builder = builder;
        this.isBP = false;

        this.bpSchedules = new Pair<>("", 0L);

        latestPaidFeesWithNotify = Coin.ZERO;
        latestParentHash = null;
        coinbaseAddress = new UscAddress(config.getMyKey().getAddress());
        minFeesNotifyInDollars = BigDecimal.valueOf(miningConfig.getMinFeesNotifyInDollars());
        gasUnitInDollars = BigDecimal.valueOf(miningConfig.getMinFeesNotifyInDollars());

    }

    @Override
    public boolean isRunning() {
        return started;
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }

        synchronized (lock) {
            started = false;
            ethereum.removeListener(blockListener);

            refreshBlockTimer = null;
            refreshBPListTimer = null;
        }
    }

    @Override
    public void start() {
        if (started) {
            return;
        }

        synchronized (lock) {
            started = true;
            blockListener = new NewBlockListener();
            ethereum.addListener(blockListener);

            if(refreshBPListTimer != null) {
                refreshBPListTimer.cancel();
            }

            refreshBPListTimer = new Timer("BP List Scheduler");
            refreshBPListTimer.schedule(new RefreshBPList(), DELAY_BETWEEN_REFRESH_BP_LIST_MS, DELAY_BETWEEN_REFRESH_BP_LIST_MS);

            if (refreshBlockTimer != null) {
                refreshBlockTimer.cancel();
            }

            refreshBlockTimer = new Timer("Refresh block for signing");
            scheduleRefreshBlockTimer(isTest);
        }
    }

    private void scheduleRefreshBlockTimer(boolean test) {

        if(test) {
            refreshBlockTimer.schedule(new RefreshBlock(), new Date(System.currentTimeMillis() + (1 * 1000)));
        } else {
            refreshBlockTimer.schedule(new RefreshBlock(), getMySchedule());
        }
    }

    private int getUniqueBpCount(JSONArray bpList) {
        List<String> addrArray = new ArrayList<>();
        for(int i = 0; i < bpList.length(); ++i) {
            String addr = bpList.getJSONObject(i).getString("ulord_addr");
            if(!addrArray.contains(addr)) {
                addrArray.add(addr);
            }
        }
        return addrArray.size();
    }

    private Date getMySchedule() {
        isBP = false;
        JSONArray bpList = getBPList();
        int nBP = getUniqueBpCount(bpList);

        String bpAddr = bpList.getJSONObject(0).getString("ulord_addr");

        // Check Ulord's network Mainnet/Testnet/Regtest
        NetworkParameters params;
        if (bpAddr.startsWith("U"))
            params = MainNetParams.get();
        else
            params = TestNet3Params.get();

        // TODO: Change params to config.getParams();
        String myUlordAddr  = UldECKey.fromPrivate(config.getMyKey().getPrivKeyBytes()).toAddress(params).toBase58() ;

        for(int i = 0; i < nBP; ++i) {
            bpAddr = bpList.getJSONObject(i).getString("ulord_addr");
            if(myUlordAddr.equals(bpAddr)) {
                isBP = true;
                long bpValidTime = bpList.getJSONObject(i).getLong("bp_valid_time");
                long mySystemTime = System.currentTimeMillis() / 1000;
                if(mySystemTime < bpValidTime) {
                    return new Date(mySystemTime * 1000);
                } else {
                    // Find next recent time for this BP
                    while (bpValidTime <= (System.currentTimeMillis() / 1000)) {
                        bpValidTime += nBP;
                    }
                    logger.info("BP Scheduled Time: " + bpValidTime + ", CurrentTime: " + System.currentTimeMillis()/1000);
                    return new Date(bpValidTime * 1000);
                }
            }
        }

        return new Date(System.currentTimeMillis() + (5 * 1000));
    }

    @Nullable
    public static byte[] readFromFile(File aFile) {
        try {
            try (FileInputStream fis = new FileInputStream(aFile)) {
                byte[] array = new byte[1024];
                int r = fis.read(array);
                array = java.util.Arrays.copyOfRange(array, 0, r);
                fis.close();
                return array;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private void processBlock1(Block b) {
        b.seal();
        ethereum.addNewMinedBlock(b);
    }

//    private SubmitBlockResult processBlock(
//            String blockHashForMergedMining,
//            UldBlock blockWithHeaderOnly,
//            UldTransaction coinbase,
//            Function<MerkleProofBuilder, byte[]> proofBuilderFunction,
//            boolean lastTag) {
//        Block newBlock;
//        Keccak256 key = new Keccak256(TypeConverter.removeZeroX(blockHashForMergedMining));
//
//        synchronized (lock) {
//            Block workingBlock = blocksWaitingforSignatures.get(key);
//
//            if (workingBlock == null) {
//                String message = "Cannot publish block, could not find hash " + blockHashForMergedMining + " in the cache";
//                logger.warn(message);
//
//                return new SubmitBlockResult("ERROR", message);
//            }
//
//            // clone the block
//            newBlock = workingBlock.cloneBlock();
//
//            logger.debug("blocksWaitingForPoW size {}", blocksWaitingforSignatures.size());
//        }
//
//        logger.info("Received block {} {}", newBlock.getNumber(), newBlock.getHash());
//
//        newBlock.seal();
//
//        if (!isValid(newBlock)) {
//
//            String message = "Invalid block supplied by blockProducer: " + newBlock.getShortHash() + " " /*+ newBlock.getShortHashForMergedMining()*/ + " at height " + newBlock.getNumber();
//            logger.error(message);
//
//            return new SubmitBlockResult("ERROR", message);
//
//        } else {
//            ImportResult importResult = ethereum.addNewMinedBlock(newBlock);
//
//            /*
//            logger.info("Mined block import result is {}: {} {} at height {}", importResult, newBlock.getShortHash(), newBlock.getShortHashForMergedMining(), newBlock.getNumber());*/
//            SubmittedBlockInfo blockInfo = new SubmittedBlockInfo(importResult, newBlock.getHash().getBytes(), newBlock.getNumber());
//
//            return new SubmitBlockResult("OK", "OK", blockInfo);
//        }
//    }

//    private boolean isValid(Block block) {
//        try {
//            return powRule.isValid(block);
//        } catch (Exception e) {
//            logger.error("Failed to validate PoW from block {}: {}", block.getShortHash(), e);
//            return false;
//        }
//    }

    @Override
    public UscAddress getCoinbaseAddress() {
        return coinbaseAddress;
    }

    public void setExtraData(byte[] extraData) {
        this.extraData = extraData;
    }

    /**
     * buildAndProcessBlock creates a block to sign based on the given block as parent.
     *
     * @param newBlockParent         the new block parent.
     * @param bpListData             BP List to store in BLM Transaction
     */
    @Override
    public void buildAndProcessBlock(@Nonnull Block newBlockParent, byte[] bpListData) {

        logger.info("Starting block to sign from parent {} {}", newBlockParent.getNumber(), newBlockParent.getHash());

        builder.setBpListData(bpListData);
        final Block newBlock = builder.build(newBlockParent, extraData);

        synchronized (lock) {

            latestParentHash = newBlockParent.getHash();
            latestBlock = newBlock;

            // process
            processBlock1(newBlock);
        }
    }

    @Override
    public Optional<Block> getLatestBlock() {
        return Optional.ofNullable(latestBlock);
    }

    @Override
    @VisibleForTesting
    public long getCurrentTimeInSeconds() {
        // this is not great, but it was the simplest way to extract BlockToSignBuilder
        return builder.getCurrentTimeInSeconds();
    }

    @Override
    public long increaseTime(long seconds) {
        // this is not great, but it was the simplest way to extract BlockToSignBuilder
        return builder.increaseTime(seconds);
    }

    class NewBlockListener extends EthereumListenerAdapter {

        @Override
        /**
         * onBlock checks if we have to build over a new block. (Only if the blockchain's best block changed).
         * This method will be called on every block added to the blockchain, even if it doesn't go to the best chain.
         * TODO(???): It would be cleaner to just                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                send this when the blockchain's best block changes.
         * **/
        // This event executes in the thread context of the caller.
        // In case of private blockProducer, it's the "Private Mining timer" task
        public void onBlock(Block block, List<TransactionReceipt> receipts) {
            if (isSyncing()) {
                return;
            }

            logger.trace("Start onBlock");
            Block bestBlock = blockchain.getBestBlock();
            //MinerWork work = currentWork;
            String bestBlockHash = bestBlock.getHashJsonString();

//            if (!work.getParentBlockHash().equals(bestBlockHash)) {
//                //logger.debug("There is a new best block: {}, number: {}", bestBlock.getShortHashForMergedMining(), bestBlock.getNumber());
//                buildAndProcessBlock(bestBlock, false);
//            } else {
//                //logger.debug("New block arrived but there is no need to build a new block to bp: {}", block.getShortHashForMergedMining());
//            }

            logger.trace("End onBlock");
        }

        private boolean isSyncing() {
            return nodeBlockProcessor.hasBetterBlockToSync();
        }
    }

    /**
     * RefreshBlocks rebuilds and reprocess the block.
     */
    private class RefreshBlock extends TimerTask {
        @Override
        public void run() {
            Block bestBlock = blockchain.getBestBlock();
            try {
                // Build block only if it is a BP
                if(isBP || isTest) {
                    logger.info("Building block to sign");
                    // TODO: Insert BPList in bpListData field.
                    buildAndProcessBlock(bestBlock, null);
                }

                scheduleRefreshBlockTimer(isTest);
            } catch (Throwable th) {
                logger.error("Unexpected error: {}", th);
                panicProcessor.panic("mserror", th.getMessage());
            }
        }
    }

    /**
     * RefreshBPList updates the BP List.
     */
    private class RefreshBPList extends TimerTask {
        @Override
        public void run() {
            try {
                JSONArray bpList = getBPList();

            } catch (Exception ex) {
                logger.error("Unexpected error: {}", ex);
            }
        }
    }

    private JSONArray getBPList() {
        UOSRpcChannel uosRpcChannel = new UOSRpcChannel(config.UosURL(), config.UosPort(), config.UosParam());
        JSONObject bpSchedule = uosRpcChannel.getBPSchedule();
        return bpSchedule.getJSONObject("round2").getJSONArray("rows");
    }
}
