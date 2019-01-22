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

import co.usc.core.Wallet;
import co.usc.ulordj.core.*;
import co.usc.config.MiningConfig;
import co.usc.config.UscMiningConstants;
import co.usc.config.UscSystemProperties;
import co.usc.core.Coin;
import co.usc.core.UscAddress;
import co.usc.crypto.Keccak256;
import co.usc.net.BlockProcessor;
import co.usc.panic.PanicProcessor;
import co.usc.ulordj.params.MainNetParams;
import co.usc.ulordj.params.TestNet3Params;
import co.usc.validators.ProofOfWorkRule;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.ECKey.ECDSASignature;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.rpc.TypeConverter;
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
import java.security.SignatureException;
import java.util.*;
import java.util.function.Function;

import co.usc.rpc.uos.UOSRpcChannel;
/**
 * The MinerServer provides support to components that perform the actual mining.
 * It builds blocks to bp and publishes blocks once a valid nonce was found by the blockProducer.
 *
 * @author Oscar Guindzberg
 */

@Component("MinerServer")
public class MinerServerImpl implements MinerServer {

    private static final Logger logger = LoggerFactory.getLogger("minerserver");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private static final int CACHE_SIZE = 20;

    private final Ethereum ethereum;
    private final Blockchain blockchain;
    private final ProofOfWorkRule powRule;
    private final BlockToSignBuilder builder;
    private Wallet wallet;
    private Timer refreshBlockTimer;

    private NewBlockListener blockListener;

    private boolean started;
    private boolean isBP;
    private boolean isTest = true;
    private byte[] extraData;

    @GuardedBy("lock")
    private LinkedHashMap<Keccak256, Block> blocksWaitingforSignatures;
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

    @Autowired
    public MinerServerImpl(
            UscSystemProperties config,
            Ethereum ethereum,
            Blockchain blockchain,
            BlockProcessor nodeBlockProcessor,
            ProofOfWorkRule powRule,
            BlockToSignBuilder builder,
            MiningConfig miningConfig,
            Wallet wallet) {
        this.config = config;
        this.ethereum = ethereum;
        this.blockchain = blockchain;
        this.nodeBlockProcessor = nodeBlockProcessor;
        this.powRule = powRule;
        this.builder = builder;
        this.wallet = wallet;
        this.isBP = false;

        blocksWaitingforSignatures = createNewBlocksWaitingList();

        latestPaidFeesWithNotify = Coin.ZERO;
        latestParentHash = null;
        coinbaseAddress = new UscAddress(config.getMyKey().getAddress());
        minFeesNotifyInDollars = BigDecimal.valueOf(miningConfig.getMinFeesNotifyInDollars());
        gasUnitInDollars = BigDecimal.valueOf(miningConfig.getMinFeesNotifyInDollars());

    }

    private LinkedHashMap<Keccak256, Block> createNewBlocksWaitingList() {
        return new LinkedHashMap<Keccak256, Block>(CACHE_SIZE) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Keccak256, Block> eldest) {
                return size() > CACHE_SIZE;
            }
        };

    }

    @VisibleForTesting
    public Map<Keccak256, Block> getBlocksWaitingforSignatures() {
        return blocksWaitingforSignatures;
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

            if (refreshBlockTimer != null) {
                refreshBlockTimer.cancel();
            }

            refreshBlockTimer = new Timer("Refresh block for signing");

            scheduleRefreshBlockTimer(isTest);
        }
    }

    private void scheduleRefreshBlockTimer(boolean test) {

        if(test) {
            refreshBlockTimer.schedule(new RefreshBlock(), new Date(System.currentTimeMillis() + (6 * 1000)));
        } else {
            refreshBlockTimer.schedule(new RefreshBlock(), getMySchedule());
        }
    }

    private Date getMySchedule() {
        isBP = false;
        JSONArray bpList = getBPList();
        int nBP = bpList.length();

        String myUlordAddr = "";

        String bpAddr = bpList.getJSONObject(0).getString("ulord_addr");

        // Check Ulord's network Mainnet/Testnet/Regtest
        if(myUlordAddr .equals("")) {
            NetworkParameters params;
            if (bpAddr.startsWith("U"))
                params = MainNetParams.get();
            else
                params = TestNet3Params.get();

            myUlordAddr  = UldECKey.fromPrivate(config.getMyKey().getPrivKeyBytes()).toAddress(params).toBase58() ;
        }

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
                        bpValidTime += nBP * 6;
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

    @Override
    public SubmitBlockResult submitSignature(String signature) {
        logger.debug("Received bpSignature {} ", signature);

//        return processBlock(
//                bpSignature,
//                blockWithHeaderOnly,
//                coinbase,
//                (pb) -> pb.buildFromMerkleHashes(blockWithHeaderOnly, merkleHashes, blockTxnCount),
//                true
//        );
        return new SubmitBlockResult("OK", "OK");
    }

//    @Override
//    public SubmitBlockResult submitUlordBlockTransactions(
//            String blockHashForMergedMining,
//            UldBlock blockWithHeaderOnly,
//            UldTransaction coinbase,
//            List<String> txHashes) {
//        logger.debug("Received tx solution with hash {} for merged mining", blockHashForMergedMining);
//
//        return processBlock(
//                blockHashForMergedMining,
//                blockWithHeaderOnly,
//                coinbase,
//                (pb) -> pb.buildFromTxHashes(blockWithHeaderOnly, txHashes),
//                true
//        );
//    }

    @Override
    public SubmitBlockResult submitUlordBlock(String blockHashForMergedMining, UldBlock ulordMergedMiningBlock) {
        return submitUlordBlock(blockHashForMergedMining, ulordMergedMiningBlock, true);
    }

    SubmitBlockResult submitUlordBlock(String blockHashForMergedMining, UldBlock ulordMergedMiningBlock, boolean lastTag) {
        logger.debug("Received block with hash {} for merged mining", blockHashForMergedMining);

        return processBlock(
                blockHashForMergedMining,
                ulordMergedMiningBlock,
                ulordMergedMiningBlock.getTransactions().get(0),
                (pb) -> pb.buildFromBlock(ulordMergedMiningBlock),
                lastTag
        );
    }

    private void processBlock1(Block b) {
        String[] accountAddressesAsHex = wallet.getAccountAddressesAsHex();

        if(accountAddressesAsHex.length == 0)
            return;
        // TODO: Add a condition to validate if it is a BP's address, and find a better solution for importing passphrase.
        wallet.unlockAccount(new UscAddress(accountAddressesAsHex[0]), "abcd1234");

        Account account = wallet.getAccount(new UscAddress(accountAddressesAsHex[0]));

        byte[] headerHash = Sha256Hash.hash(b.getHeader().getEncoded());
        ECDSASignature signature = signBlock(Hex.toHexString(headerHash), account.getEcKey());

        b.addSignature(signature);

        b.seal();
        ethereum.addNewMinedBlock(b);
    }

    private boolean validateSignature(ECDSASignature signature, byte[] message) {

        try {
            ECKey ecKey = ECKey.signatureToKey(message, signature.toBase64());
            //System.out.println(Hex.toHexString(ecKey.getPubKey()));
            return ECKey.verify(message, signature, ecKey.getPubKey());
        } catch (SignatureException e) {
            e.printStackTrace();
            return false;
        }
    }

    private ECDSASignature signBlock(String data, ECKey ecKey) {
        byte[] dataHash = TypeConverter.stringHexToByteArray(data);
        ECDSASignature signature = ecKey.sign(dataHash);

        return signature;
    }

    private SubmitBlockResult processBlock(
            String blockHashForMergedMining,
            UldBlock blockWithHeaderOnly,
            UldTransaction coinbase,
            Function<MerkleProofBuilder, byte[]> proofBuilderFunction,
            boolean lastTag) {
        Block newBlock;
        Keccak256 key = new Keccak256(TypeConverter.removeZeroX(blockHashForMergedMining));

        synchronized (lock) {
            Block workingBlock = blocksWaitingforSignatures.get(key);

            if (workingBlock == null) {
                String message = "Cannot publish block, could not find hash " + blockHashForMergedMining + " in the cache";
                logger.warn(message);

                return new SubmitBlockResult("ERROR", message);
            }

            // clone the block
            newBlock = workingBlock.cloneBlock();

            logger.debug("blocksWaitingForPoW size {}", blocksWaitingforSignatures.size());
        }

        logger.info("Received block {} {}", newBlock.getNumber(), newBlock.getHash());

        newBlock.seal();

        if (!isValid(newBlock)) {

            String message = "Invalid block supplied by blockProducer: " + newBlock.getShortHash() + " " /*+ newBlock.getShortHashForMergedMining()*/ + " at height " + newBlock.getNumber();
            logger.error(message);

            return new SubmitBlockResult("ERROR", message);

        } else {
            ImportResult importResult = ethereum.addNewMinedBlock(newBlock);

            /*
            logger.info("Mined block import result is {}: {} {} at height {}", importResult, newBlock.getShortHash(), newBlock.getShortHashForMergedMining(), newBlock.getNumber());*/
            SubmittedBlockInfo blockInfo = new SubmittedBlockInfo(importResult, newBlock.getHash().getBytes(), newBlock.getNumber());

            return new SubmitBlockResult("OK", "OK", blockInfo);
        }
    }

    private boolean isValid(Block block) {
        try {
            return powRule.isValid(block);
        } catch (Exception e) {
            logger.error("Failed to validate PoW from block {}: {}", block.getShortHash(), e);
            return false;
        }
    }

    public static byte[] compressCoinbase(byte[] ulordMergedMiningCoinbaseTransactionSerialized) {
        return compressCoinbase(ulordMergedMiningCoinbaseTransactionSerialized, true);
    }

    public static byte[] compressCoinbase(byte[] ulordMergedMiningCoinbaseTransactionSerialized, boolean lastOccurrence) {
        List<Byte> coinBaseTransactionSerializedAsList = java.util.Arrays.asList(ArrayUtils.toObject(ulordMergedMiningCoinbaseTransactionSerialized));
        List<Byte> tagAsList = java.util.Arrays.asList(ArrayUtils.toObject(UscMiningConstants.USC_TAG));

        int uscTagPosition;
        if (lastOccurrence) {
            uscTagPosition = Collections.lastIndexOfSubList(coinBaseTransactionSerializedAsList, tagAsList);
        } else {
            uscTagPosition = Collections.indexOfSubList(coinBaseTransactionSerializedAsList, tagAsList);
        }

        int remainingByteCount = ulordMergedMiningCoinbaseTransactionSerialized.length - uscTagPosition - UscMiningConstants.USC_TAG.length - UscMiningConstants.BLOCK_HEADER_HASH_SIZE;
        if (remainingByteCount > UscMiningConstants.MAX_BYTES_AFTER_MERGED_MINING_HASH) {
            throw new IllegalArgumentException("More than 128 bytes after USC tag");
        }
        int sha256Blocks = uscTagPosition / 64;
        int bytesToHash = sha256Blocks * 64;
        SHA256Digest digest = new SHA256Digest();
        digest.update(ulordMergedMiningCoinbaseTransactionSerialized, 0, bytesToHash);
        byte[] hashedContent = digest.getEncodedState();
        byte[] trimmedHashedContent = new byte[UscMiningConstants.MIDSTATE_SIZE_TRIMMED];
        System.arraycopy(hashedContent, 8, trimmedHashedContent, 0, UscMiningConstants.MIDSTATE_SIZE_TRIMMED);
        byte[] unHashedContent = new byte[ulordMergedMiningCoinbaseTransactionSerialized.length - bytesToHash];
        System.arraycopy(ulordMergedMiningCoinbaseTransactionSerialized, bytesToHash, unHashedContent, 0, unHashedContent.length);
        return Arrays.concatenate(trimmedHashedContent, unHashedContent);
    }

    @Override
    public UscAddress getCoinbaseAddress() {
        return coinbaseAddress;
    }

    /**
     * getWork returns the latest MinerWork for miners. Subsequent calls to this function with no new work will return
     * currentWork with the notify flag turned off. (they will be different objects too).
     *
     * This method must be called with MinerServer started. That and the fact that work is never set to null
     * will ensure that currentWork is not null.
     *
     * @return the latest MinerWork available.
     */
    @Override
    public MinerWork getWork() {
        MinerWork work = currentWork;

        if (work.getNotify()) {
            /**
             * Set currentWork.notify to false for the next time this function is called.
             * By doing it this way, we avoid taking the lock every time, we just take it once per MinerWork.
             * We have to take the lock to reassign currentWork, but it might have happened that
             * the currentWork got updated when we acquired the lock. In that case, we should just return the new
             * currentWork, regardless of what it is.
             */
            synchronized (lock) {
                if (currentWork != work) {
                    return currentWork;
                }
                currentWork = new MinerWork(currentWork.getBlockHashForMergedMining(), currentWork.getTarget(),
                        currentWork.getFeesPaidToMiner(), false, currentWork.getParentBlockHash());
            }
        }
        return work;
    }

    @VisibleForTesting
    public void setWork(MinerWork work) {
        this.currentWork = work;
    }

    public void setExtraData(byte[] extraData) {
        this.extraData = extraData;
    }

    /**
     * buildBlockToSign creates a block to sign based on the given block as parent.
     *
     * @param newBlockParent         the new block parent.
     * @param createCompetitiveBlock used for testing.
     */
    @Override
    public void buildBlockToSign(@Nonnull Block newBlockParent, boolean createCompetitiveBlock) {

        // See BlockChainImpl.calclBloom() if blocks has txs
        if (createCompetitiveBlock) {
            // Just for testing, bp on top of bestblock's parent
            newBlockParent = blockchain.getBlockByHash(newBlockParent.getParentHash().getBytes());
        }

        logger.info("Starting block to sign from parent {} {}", newBlockParent.getNumber(), newBlockParent.getHash());

        final Block newBlock = builder.build(newBlockParent, extraData);

        synchronized (lock) {
            Keccak256 parentHash = newBlockParent.getHash();

            latestParentHash = parentHash;
            latestBlock = newBlock;

            //Keccak256 latestBlockHashWaitingForPoW = new Keccak256(newBlock.getHashForMergedMining());

            //TODO DPOS: Possible broadcast area for block.
            //blocksWaitingforSignatures.put(latestBlockHashWaitingForPoW, latestBlock);

            // process
            processBlock1(newBlock);
            logger.debug("blocksWaitingForPoW size {}", blocksWaitingforSignatures.size());
        }

        //logger.debug("Built block {}. Parent {}", newBlock.getShortHashForMergedMining(), newBlockParent.getShortHashForMergedMining());
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
         * TODO(???): It would be cleaner to just send this when the blockchain's best block changes.
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
//                buildBlockToSign(bestBlock, false);
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
     * RefreshBlocks rebuilds the block to sign.
     */
    private class RefreshBlock extends TimerTask {
        @Override
        public void run() {
            Block bestBlock = blockchain.getBestBlock();
            try {
                // Build block only if it is a BP
                if(isBP || isTest) {
                    logger.info("Building block to sign");
                    buildBlockToSign(bestBlock, false);
                }

                scheduleRefreshBlockTimer(isTest);
            } catch (Throwable th) {
                logger.error("Unexpected error: {}", th);
                panicProcessor.panic("mserror", th.getMessage());
            }
        }
    }

    private JSONArray getBPList() {
        String rpcUrl = "http://114.67.37.2:20580/v1/chain/get_table_rows";
        String urlParameters = "{\"scope\":\"uosclist\",\"code\":\"uosio\",\"table\":\"uosclist\",\"json\":\"true\"}";
        String bpList = UOSRpcChannel.requestBPList(rpcUrl, urlParameters);
        return new JSONObject(bpList).getJSONArray("rows");
    }
}
