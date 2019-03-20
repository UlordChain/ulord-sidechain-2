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
import co.usc.config.BpConfig;
import co.usc.config.UscSystemProperties;
import co.usc.core.Coin;
import co.usc.core.UscAddress;
import co.usc.crypto.Keccak256;
import co.usc.net.BlockProcessor;
import co.usc.panic.PanicProcessor;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.config.Constants;
import org.ethereum.core.*;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.util.Utils;
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
import java.time.Instant;
import java.util.*;

import co.usc.rpc.uos.UOSRpcChannel;

/**
 * The BpServer provides support to components that perform the actual mining.
 * It builds blocks to bp and publishes blocks once a valid nonce was found by the blockProducer.
 *
 * @author Oscar Guindzberg
 */

@Component("BpServer")
public class BpServerImpl implements BpServer {

    private static final Logger logger = LoggerFactory.getLogger("bpserver");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private static final int CACHE_SIZE = 20;

    private final Ethereum ethereum;
    private final Blockchain blockchain;
    private final BlockToSignBuilder builder;
    private Timer scheduleAndBuildTimer;
    private Timer produceBlockTimer;

    private NewBlockListener blockListener;

    private boolean started;
    private byte[] extraData;

    @GuardedBy("lock")
    private Keccak256 latestParentHash;
    @GuardedBy("lock")
    private Block latestBlock;
    @GuardedBy("lock")
    private Coin latestPaidFeesWithNotify;
    @GuardedBy("lock")
    private final Object lock = new Object();

    private final UscAddress coinbaseAddress;
    private final BigDecimal minFeesNotifyInDollars;
    private final BigDecimal gasUnitInDollars;

    private final BlockProcessor nodeBlockProcessor;

    private final UscSystemProperties config;

    private final UOSRpcChannel uosRpcChannel;

    @Autowired
    public BpServerImpl(
            UscSystemProperties config,
            Ethereum ethereum,
            Blockchain blockchain,
            BlockProcessor nodeBlockProcessor,
            BlockToSignBuilder builder,
            BpConfig bpConfig,
            UOSRpcChannel uosRpcChannel
            ) {
        this.config = config;
        this.ethereum = ethereum;
        this.blockchain = blockchain;
        this.nodeBlockProcessor = nodeBlockProcessor;
        this.builder = builder;
        this.uosRpcChannel = uosRpcChannel;

        latestPaidFeesWithNotify = Coin.ZERO;
        latestParentHash = null;
        coinbaseAddress = new UscAddress(config.getMyKey().getAddress());
        minFeesNotifyInDollars = BigDecimal.valueOf(bpConfig.getMinFeesNotifyInDollars());
        gasUnitInDollars = BigDecimal.valueOf(bpConfig.getMinFeesNotifyInDollars());

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

            scheduleAndBuildTimer = null;
            produceBlockTimer = null;
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

            // Set up timer to produce block;
            if(produceBlockTimer!= null) {
                produceBlockTimer.cancel();
            }
            produceBlockTimer = new Timer("Produce Block Timer");

            if(scheduleAndBuildTimer!= null) {
                scheduleAndBuildTimer.cancel();
            }
            scheduleAndBuildTimer = new Timer("BP Scheduler");
            scheduleAndBuildTimer.schedule(new ScheduleAndBuild(), new Date(System.currentTimeMillis() + (1000 * 5)));
        }
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
            // This function is no longer needed
        }

        private boolean isSyncing() {
            return nodeBlockProcessor.hasBetterBlockToSync();
        }
    }

    private class ProduceBlock extends TimerTask {

        List<String> producers;
        Blockchain blockchain;
        ProduceBlock(List<String> producers, Blockchain bestchain) {
            this.producers = producers;
            this.blockchain = bestchain;
        }

        @Override
        public void run() {
            //System.out.println("Producing block");
            byte[] bpListData = Utils.encodeBpList(producers);
            buildAndProcessBlock(blockchain.getBestBlock(), bpListData);
        }
    }

    /**
     * RefreshBPList updates the BP List.
     */
    private class ScheduleAndBuild extends TimerTask {
        @Override
        public void run() {
            try {
                JSONArray bpList = uosRpcChannel.getBPList();
                logger.debug("Received BP List: " + bpList.toString());
                List<String> producers = new ArrayList<>();
                for (int i = 0; i < bpList.length(); ++i) {
                    JSONObject jsonObject = bpList.getJSONObject(i);
                    String uosPubKey = jsonObject.getString("ulord_addr");
                    producers.add(Utils.UosPubKeyToUlord(uosPubKey));
                }

                Block bestBlock = blockchain.getBestBlock();

                // If the best block is genesis, build a block with the latest BP List.
                // This BP List will be for the next round.
                long futureSchedule = 0;
                if(bestBlock.isGenesis()) {
                    byte[] bpListData = Utils.encodeBpList(producers);
                    buildAndProcessBlock(bestBlock, bpListData);
                    futureSchedule = Instant.now().toEpochMilli();
                } else {
                    // 1. Check if this node is one of the BP's
                    // 2. Calculate this bp's future schedule and schedule to produce block.

                    if(!Utils.isBp(producers, config)) return;

                    futureSchedule = getFutureSchedule(producers);

                    if(futureSchedule != -1)
                        produceBlockTimer.schedule(new ProduceBlock(producers, blockchain), new Date(futureSchedule));
                    else {
                        futureSchedule = Instant.now().toEpochMilli();
                    }
                }
                scheduleAndBuildTimer.schedule(new ScheduleAndBuild(), new Date(futureSchedule + Constants.getBlockIntervalMs() * (Constants.getProducerRepetitions() + 1)));
            } catch (Exception ex) {
                // Try to schedule for the next round.
                scheduleAndBuildTimer.schedule(new ScheduleAndBuild(), new Date(Instant.now().toEpochMilli() + Constants.getBlockIntervalMs() * Constants.getProducerRepetitions()));
                logger.error("Unexpected error: {}", ex);
            }
        }
    }

    // Returns scheduled producer's key
    private long getFutureSchedule(List<String> bpList) {
        long time = Instant.now().toEpochMilli();

        while(true) {
            long blockTimestamp = Constants.getBlockTimestampEpoch();
            long blockInterval = Constants.getBlockIntervalMs();
            int producerRepetitions = Constants.getProducerRepetitions();

            int bpIndex = Utils.getBpScheduledIndex(time, blockTimestamp, blockInterval, producerRepetitions, bpList.size());
            if(bpList.get(bpIndex).equals(UldECKey.fromPrivate(config.getMyKey().getPrivKeyBytes()).getPublicKeyAsHex()))
                break;
            else if(time > Instant.now().toEpochMilli() * producerRepetitions * bpList.size() * blockInterval * 2) {
                // return -1 if the slot is not found within 2 rounds time in the future.
                return -1;
            }
            time += 50;
        }
        logger.debug("BP Scheduled for: " + new Date(time));
        return time;
    }


}
