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

import co.usc.BpListManager.BlmTransaction;
import co.usc.config.*;
import co.usc.core.Coin;
import co.usc.core.UscAddress;
import co.usc.core.bc.BlockExecutor;
import co.usc.remasc.RemascTransaction;
import co.usc.ulordj.core.Sha256Hash;
import co.usc.validators.BlockValidationRule;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.db.BlockStore;
import org.ethereum.db.ReceiptStore;
import org.ethereum.rpc.TypeConverter;
import org.ethereum.vm.PrecompiledContracts;
import org.ethereum.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Clock;
import java.util.*;

/**
 * This component helps build a new block to be signed by the block producers.
 * It can also be used to generate a new block from the pending state, which is useful
 * in places like Web3 with the 'pending' parameter.
 */
@Component
public class BlockToSignBuilder {
    private static final Logger logger = LoggerFactory.getLogger("blocktosignbuilder");

    private final BpConfig bpConfig;
    private final Repository repository;
    private final BlockStore blockStore;
    private final TransactionPool transactionPool;
    private final GasLimitCalculator gasLimitCalculator;
    private final BlockValidationRule validationRules;

    private final Clock clock;
    private final MinimumGasPriceCalculator minimumGasPriceCalculator;
    private final BpUtils bpUtils;
    private final BlockExecutor executor;

    private final UscSystemProperties config;

    private final Coin bpMinGasPriceTarget;

    private long timeAdjustment;
    private long minimumAcceptableTime;

    private byte[] bpListData;

    @Autowired
    public BlockToSignBuilder(
            BpConfig bpConfig,
            Repository repository,
            BlockStore blockStore,
            TransactionPool transactionPool,
            GasLimitCalculator gasLimitCalculator,
            @Qualifier("bpServerBlockValidation") BlockValidationRule validationRules,
            UscSystemProperties config,
            ReceiptStore receiptStore) {
        this.bpConfig = Objects.requireNonNull(bpConfig);
        this.repository = Objects.requireNonNull(repository);
        this.blockStore = Objects.requireNonNull(blockStore);
        this.transactionPool = Objects.requireNonNull(transactionPool);
        this.gasLimitCalculator = Objects.requireNonNull(gasLimitCalculator);
        this.validationRules = Objects.requireNonNull(validationRules);

        this.clock = Clock.systemUTC();
        this.minimumGasPriceCalculator = new MinimumGasPriceCalculator();
        this.bpUtils = new BpUtils();
        final ProgramInvokeFactoryImpl programInvokeFactory = new ProgramInvokeFactoryImpl();
        this.executor = new BlockExecutor(repository, (tx1, txindex1, coinbase, track1, block1, totalGasUsed1) -> new TransactionExecutor(
                tx1,
                txindex1,
                block1.getCoinbase(),
                track1,
                blockStore,
                receiptStore,
                programInvokeFactory,
                block1,
                null,
                totalGasUsed1,
                config.getVmConfig(),
                config.getBlockchainConfig(),
                config.playVM(),
                config.isRemascEnabled(),
                config.vmTrace(),
                new PrecompiledContracts(config),
                config.databaseDir(),
                config.vmTraceDir(),
                config.vmTraceCompressed()
        ));
        this.config = config;
        this.bpMinGasPriceTarget = Coin.valueOf(bpConfig.getMinGasPriceTarget());
    }

    /**
     * build creates a block to sign based on the given block as parent.
     *
     * @param newBlockParent the new block parent.
     * @param extraData      extra data to pass to the block being built
     */
    public Block build(Block newBlockParent, byte[] extraData) {
        Coin minimumGasPrice = minimumGasPriceCalculator.calculate(
                newBlockParent.getMinimumGasPrice(),
                bpMinGasPriceTarget
        );

        final List<Transaction> txsToRemove = new ArrayList<>();
        final List<Transaction> txs = getTransactions(txsToRemove, newBlockParent, minimumGasPrice);
        minimumAcceptableTime = newBlockParent.getTimestamp() + 1;

        final Block newBlock = createBlock(newBlockParent, txs, minimumGasPrice);

        newBlock.setExtraData(extraData);
        removePendingTransactions(txsToRemove);
        executor.executeAndFill(newBlock, newBlockParent);
        return newBlock;
    }

    private List<Transaction> getTransactions(List<Transaction> txsToRemove, Block parent, Coin minGasPrice) {
        logger.debug("getting transactions from pending state");
        List<Transaction> txs = bpUtils.getAllTransactions(transactionPool);
        logger.debug("{} transaction(s) collected from pending state", txs.size());

        Transaction BlmTx = new BlmTransaction(parent.getNumber() + 1, getBpListData());

        if(!txs.contains(BlmTx))
            txs.add(BlmTx);

        Transaction remascTx = new RemascTransaction(parent.getNumber() + 1);
        txs.add(remascTx);

        Map<UscAddress, BigInteger> accountNonces = new HashMap<>();

        Repository originalRepo = repository.getSnapshotTo(parent.getStateRoot());

        return bpUtils.filterTransactions(txsToRemove, txs, accountNonces, originalRepo, minGasPrice);
    }

    private void removePendingTransactions(List<Transaction> transactions) {
        transactionPool.removeTransactions(transactions);
    }

    private Block createBlock(
            Block newBlockParent,
            List<Transaction> txs,
            Coin minimumGasPrice) {

        final BlockHeader newHeader = createHeader(newBlockParent, txs, minimumGasPrice);
        final Block newBlock = new Block(newHeader, txs);
        ECKey bpKey = config.getMyKey();
        byte[] headerHash = Sha256Hash.hash(newBlock.getHeader().getEncoded());
        ECKey.ECDSASignature signature = signBlock(Hex.toHexString(headerHash), bpKey);
        newBlock.addSignature(signature);

        return validationRules.isValid(newBlock) ? newBlock : null;
    }

    private ECKey.ECDSASignature signBlock(String data, ECKey ecKey) {
        byte[] dataHash = TypeConverter.stringHexToByteArray(data);
        return ecKey.sign(dataHash);
    }

    private BlockHeader createHeader(
            Block newBlockParent,
            List<Transaction> txs,
            Coin minimumGasPrice) {

        final long timestampSeconds = this.getCurrentTimeInSeconds();

        // Set gas limit before executing block
        BigInteger minGasLimit = BigInteger.valueOf(bpConfig.getGasLimit().getMininimum());
        BigInteger targetGasLimit = BigInteger.valueOf(bpConfig.getGasLimit().getTarget());
        BigInteger parentGasLimit = new BigInteger(1, newBlockParent.getGasLimit());
        BigInteger gasUsed = BigInteger.valueOf(newBlockParent.getGasUsed());
        boolean forceLimit = bpConfig.getGasLimit().isTargetForced();
        BigInteger gasLimit = gasLimitCalculator.calculateBlockGasLimit(parentGasLimit,
                                                                        gasUsed, minGasLimit, targetGasLimit, forceLimit);

        final BlockHeader newHeader = new BlockHeader(
                newBlockParent.getHash().getBytes(),
                config.getMyKey().getAddress(),
                new Bloom().getData(),
                newBlockParent.getNumber() + 1,
                gasLimit.toByteArray(),
                0,
                timestampSeconds,
                new byte[]{},
                minimumGasPrice.getBytes()
        );
        newHeader.setTransactionsRoot(Block.getTxTrie(txs).getHash().getBytes());
        return newHeader;
    }

    // Note that this needs to be refactored.
    public long getCurrentTimeInSeconds() {
        long ret = clock.millis() / 1000 + timeAdjustment;
        return Long.max(ret, minimumAcceptableTime);
    }

    // Note that this needs to be refactored.
    public long increaseTime(long seconds) {
        if (seconds <= 0) {
            return timeAdjustment;
        }

        timeAdjustment += seconds;
        return timeAdjustment;
    }

    public byte[] getBpListData() {
        return bpListData;
    }

    public void setBpListData(byte[] bpListData) {
        this.bpListData = bpListData;
    }
}
