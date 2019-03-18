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

package org.ethereum.config;

import co.usc.config.BridgeConstants;
import co.usc.config.BridgeTestNetConstants;
import co.usc.core.UscAddress;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Describes different constants specific for a blockchain
 *
 * Created by Anton Nashatyrev on 25.02.2016.
 */
public class Constants {
    private static final int MAX_CONTRACT_SIZE = 0x6000;
    // we defined it be large enough for to allow large tx and also to have space still to operate on vm
    private static final BigInteger TRANSACTION_GAS_CAP = BigDecimal.valueOf(Math.pow(2,  60)).toBigInteger();
    private static final int MAX_ADDRESS_BYTE_LENGTH = 20;
    private int maximumExtraDataSize = 32;
    private int minGasLimit = 150000000;
    private int gasLimitBoundDivisor = 1024;

    private static final long BLOCK_INTERVAL_MS = 1000l;
    private static final long BLOCK_INTERVAL_US = BLOCK_INTERVAL_MS * 1000;
    private static final long BLOCK_TIMESTAMP_EPOCH = 946684800000l; // epoch is year 2000.
    private static final int PRODUCER_REPETITIONS = 6;

    private int bestNumberDiffLimit = 100;

    private int newBlockMaxSecondsInTheFuture = 126;

    private final BigInteger minimumPayableGas = BigInteger.valueOf(200000);
    private final BigInteger federatorMinimumPayableGas = BigInteger.valueOf(50000);

    private static final BigInteger SECP256K1N = new BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);

    private static final UscAddress BURN_ADDRESS = new UscAddress("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");

    private static final byte CHAIN_ID = 50;

    public static BigInteger getTransactionGasCap() {
        return TRANSACTION_GAS_CAP;
    }

    public static int getMaxContractSize() {
        return MAX_CONTRACT_SIZE;
    }

    public static int getMaxAddressByteLength() {
        return MAX_ADDRESS_BYTE_LENGTH;
    }

    public BigInteger getInitialNonce() {
        return BigInteger.ZERO;
    }

    public int getMaximumExtraDataSize() {
        return maximumExtraDataSize;
    }

    public int getMinGasLimit() {
        return minGasLimit;
    }

    public int getGasLimitBoundDivisor() {
        return gasLimitBoundDivisor;
    }

    public static long getBlockIntervalMs() {
        return BLOCK_INTERVAL_MS;
    }

    public static int getProducerRepetitions() {
        return PRODUCER_REPETITIONS;
    }

    public static long getBlockIntervalUs() {
        return BLOCK_INTERVAL_US;
    }

    public static long getBlockTimestampEpoch() {
        return BLOCK_TIMESTAMP_EPOCH;
    }

    public int getBestNumberDiffLimit() {
        return bestNumberDiffLimit;
    }

    public static BigInteger getSECP256K1N() {
        return SECP256K1N;
    }

    public BridgeConstants getBridgeConstants() { return BridgeTestNetConstants.getInstance(); }

    public int getNewBlockMaxSecondsInTheFuture() {
        return this.newBlockMaxSecondsInTheFuture;
    }

    public UscAddress getBurnAddress() { return Constants.BURN_ADDRESS; }

    /**
     * EIP155: https://github.com/ethereum/EIPs/issues/155
     */
    public byte getChainId() { return Constants.CHAIN_ID; }

    public BigInteger getMinimumPayableGas() {
        return minimumPayableGas;
    }

    public BigInteger getFederatorMinimumPayableGas() {
        return federatorMinimumPayableGas;
    }
}
