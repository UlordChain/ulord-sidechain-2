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
package org.ethereum.core;

import co.usc.config.BridgeConstants;
import co.usc.core.Coin;
import co.usc.core.UscAddress;
import co.usc.crypto.Keccak256;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.bouncycastle.pqc.math.linearalgebra.ByteUtils;
import org.bouncycastle.util.BigIntegers;
import org.ethereum.crypto.HashUtil;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPList;
import org.ethereum.util.Utils;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.List;

import static org.ethereum.crypto.HashUtil.EMPTY_TRIE_HASH;
import static org.ethereum.util.ByteUtil.toHexString;

/**
 * Block header is a value object containing
 * the basic information of a block
 */
public class BlockHeader {


    /* The SHA3 256-bit hash of the parent block, in its entirety */
    private byte[] parentHash;
    /* The 160-bit address to which all fees collected from the
     * successful mining of this block be transferred; formally */
    private UscAddress coinbase;
    /* The SHA3 256-bit hash of the root node of the state trie,
     * after all transactions are executed and finalisations applied */
    private byte[] stateRoot;
    /* The SHA3 256-bit hash of the root node of the trie structure
     * populated with each transaction in the transaction
     * list portion, the trie is populate by [key, val] --> [rlp(index), rlp(tx_recipe)]
     * of the block */
    private byte[] txTrieRoot;
    /* The SHA3 256-bit hash of the root node of the trie structure
     * populated with each transaction recipe in the transaction recipes
     * list portion, the trie is populate by [key, val] --> [rlp(index), rlp(tx_recipe)]
     * of the block */
    private byte[] receiptTrieRoot;
    /* The bloom filter for the logs of the block */
    private byte[] logsBloom;
    /* A scalar value equalBytes to the reasonable output of Unix's time()
     * at this block's inception */
    private long timestamp;
    /* A scalar value equalBytes to the number of ancestor blocks.
     * The genesis block has a number of zero */
    private long number;
    /* A scalar value equalBytes to the current limit of gas expenditure per block */
    private byte[] gasLimit;
    /* A scalar value equalBytes to the total gas used in transactions in this block */
    private long gasUsed;
    /* A scalar value equalBytes to the total paid fees in transactions in this block */
    private Coin paidFees;

    /* An arbitrary byte array containing data relevant to this block.
     * With the exception of the genesis block, this must be 32 bytes or fewer */
    private byte[] extraData;

    /* The SHA3 256-bit hash of signatures of all the BPs */
    private byte[] signaturesRoot;

    /**
     * The mgp for a tx to be included in the block.
     */
    private Coin minimumGasPrice;

    /* Indicates if this block header cannot be changed */
    private volatile boolean sealed;

    public BlockHeader(byte[] encoded, boolean sealed) {
        this(RLP.decodeList(encoded), sealed);
    }

    public BlockHeader(RLPList rlpHeader, boolean sealed) {
        // TODO fix old tests that have other sizes
        if (rlpHeader.size() != 14) {
            throw new IllegalArgumentException(String.format(
                    "A block header must have 14 elements but it had %d",
                    rlpHeader.size()
            ));
        }

        this.parentHash = rlpHeader.get(0).getRLPData();
        this.coinbase = RLP.parseUscAddress(rlpHeader.get(1).getRLPData());
        this.stateRoot = rlpHeader.get(2).getRLPData();
        if (this.stateRoot == null) {
            this.stateRoot = EMPTY_TRIE_HASH;
        }

        this.txTrieRoot = rlpHeader.get(3).getRLPData();
        if (this.txTrieRoot == null) {
            this.txTrieRoot = EMPTY_TRIE_HASH;
        }

        this.receiptTrieRoot = rlpHeader.get(4).getRLPData();
        if (this.receiptTrieRoot == null) {
            this.receiptTrieRoot = EMPTY_TRIE_HASH;
        }

        this.signaturesRoot = rlpHeader.get(5).getRLPData();
        this.logsBloom = rlpHeader.get(6).getRLPData();

        this.number = parseBigInteger(rlpHeader.get(7).getRLPData()).longValueExact();

        this.gasLimit = rlpHeader.get(8).getRLPData();
        this.gasUsed = parseBigInteger(rlpHeader.get(9).getRLPData()).longValueExact();
        this.timestamp = parseBigInteger(rlpHeader.get(10).getRLPData()).longValueExact();

        this.extraData = rlpHeader.get(11).getRLPData();

        this.paidFees = RLP.parseCoin(rlpHeader.get(12).getRLPData());
        this.minimumGasPrice = RLP.parseSignedCoinNonNullZero(rlpHeader.get(13).getRLPData());

        this.sealed = sealed;
    }

    public BlockHeader(byte[] parentHash, byte[] coinbase,
                       byte[] logsBloom, long number,
                       byte[] gasLimit, long gasUsed, long timestamp,
                       byte[] extraData,
                       byte[] minimumGasPrice) {
        this.parentHash = parentHash;
        this.coinbase = new UscAddress(coinbase);
        this.logsBloom = logsBloom;
        this.number = number;
        this.gasLimit = gasLimit;
        this.gasUsed = gasUsed;
        this.timestamp = timestamp;
        this.extraData = extraData;
        this.stateRoot = ByteUtils.clone(EMPTY_TRIE_HASH);
        this.minimumGasPrice = RLP.parseSignedCoinNonNullZero(minimumGasPrice);
        this.receiptTrieRoot = ByteUtils.clone(EMPTY_TRIE_HASH);
        this.paidFees = Coin.ZERO;
    }

    @VisibleForTesting
    public boolean isSealed() {
        return this.sealed;
    }

    public void seal() {
        this.sealed = true;
    }

    public BlockHeader cloneHeader(BridgeConstants constants) {
        return new BlockHeader((RLPList) RLP.decode2(this.getEncoded()).get(0), false);
    }

    public boolean isGenesis() {
        return this.getNumber() == Genesis.NUMBER;
    }

    public Keccak256 getParentHash() {
        return new Keccak256(parentHash);
    }

    public UscAddress getCoinbase() {
        return this.coinbase;
    }

    public byte[] getStateRoot() {
        return stateRoot;
    }

    public void setStateRoot(byte[] stateRoot) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter state root");
        }

        this.stateRoot = stateRoot;
    }

    public byte[] getTxTrieRoot() {
        return txTrieRoot;
    }

    public void setReceiptsRoot(byte[] receiptTrieRoot) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter receipts root");
        }

        this.receiptTrieRoot = receiptTrieRoot;
    }

    public byte[] getReceiptsRoot() {
        return receiptTrieRoot;
    }

    public void setTransactionsRoot(byte[] stateRoot) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter transactions root");
        }

        this.txTrieRoot = stateRoot;
    }


    public byte[] getLogsBloom() {
        return logsBloom;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter timestamp");
        }

        this.timestamp = timestamp;
    }

    public long getNumber() {
        return number;
    }

    public void setNumber(long number) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter number");
        }

        this.number = number;
    }

    public byte[] getGasLimit() {
        return gasLimit;
    }

    public void setGasLimit(byte[] gasLimit) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter gas limit");
        }

        this.gasLimit = gasLimit;
    }

    public long getGasUsed() {
        return gasUsed;
    }

    public void setPaidFees(Coin paidFees) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter paid fees");
        }

        this.paidFees = paidFees;
    }

    public Coin getPaidFees() {
        return this.paidFees;
    }

    public void setGasUsed(long gasUsed) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter gas used");
        }

        this.gasUsed = gasUsed;
    }

    public byte[] getExtraData() {
        return extraData;
    }

    public void setLogsBloom(byte[] logsBloom) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter logs bloom");
        }

        this.logsBloom = logsBloom;
    }

    public void setExtraData(byte[] extraData) {
        /* A sealed block header is immutable, cannot be changed */
        if (this.sealed) {
            throw new SealedBlockHeaderException("trying to alter extra data");
        }

        this.extraData = extraData;
    }

    public Keccak256 getHash() {
        return new Keccak256(HashUtil.keccak256(getEncoded()));
    }

    @Nullable
    public Coin getMinimumGasPrice() {
        return this.minimumGasPrice;
    }

    public byte[] getEncoded() {
        byte[] parentHash = RLP.encodeElement(this.parentHash);

        byte[] coinbase = RLP.encodeUscAddress(this.coinbase);

        byte[] stateRoot = RLP.encodeElement(this.stateRoot);

        if (txTrieRoot == null) {
            this.txTrieRoot = EMPTY_TRIE_HASH;
        }

        byte[] txTrieRoot = RLP.encodeElement(this.txTrieRoot);

        if (receiptTrieRoot == null) {
            this.receiptTrieRoot = EMPTY_TRIE_HASH;
        }

        byte[] receiptTrieRoot = RLP.encodeElement(this.receiptTrieRoot);

        byte[] signaturesRoot = RLP.encodeElement(this.signaturesRoot);
        byte[] logsBloom = RLP.encodeElement(this.logsBloom);
        byte[] number = RLP.encodeBigInteger(BigInteger.valueOf(this.number));
        byte[] gasLimit = RLP.encodeElement(this.gasLimit);
        byte[] gasUsed = RLP.encodeBigInteger(BigInteger.valueOf(this.gasUsed));
        byte[] timestamp = RLP.encodeBigInteger(BigInteger.valueOf(this.timestamp));
        byte[] extraData = RLP.encodeElement(this.extraData);
        byte[] paidFees = RLP.encodeCoin(this.paidFees);
        byte[] mgp = RLP.encodeSignedCoinNonNullZero(this.minimumGasPrice);
        List<byte[]> fieldToEncodeList = Lists.newArrayList(parentHash, coinbase,
                stateRoot, txTrieRoot, receiptTrieRoot, signaturesRoot, logsBloom, number,
                gasLimit, gasUsed, timestamp, extraData, paidFees, mgp);


        return RLP.encodeList(fieldToEncodeList.toArray(new byte[][]{}));
    }

    public String toString() {
        return toStringWithSuffix("\n");
    }

    private String toStringWithSuffix(final String suffix) {
        StringBuilder toStringBuff = new StringBuilder();
        toStringBuff.append("  parentHash=").append(toHexString(parentHash)).append(suffix);
        toStringBuff.append("  coinbase=").append(coinbase).append(suffix);
        toStringBuff.append("  stateRoot=").append(toHexString(stateRoot)).append(suffix);
        toStringBuff.append("  txTrieHash=").append(toHexString(txTrieRoot)).append(suffix);
        toStringBuff.append("  receiptsTrieHash=").append(toHexString(receiptTrieRoot)).append(suffix);
        toStringBuff.append("  number=").append(number).append(suffix);
        toStringBuff.append("  gasLimit=").append(toHexString(gasLimit)).append(suffix);
        toStringBuff.append("  gasUsed=").append(gasUsed).append(suffix);
        toStringBuff.append("  timestamp=").append(timestamp).append(" (").append(Utils.longToDateTime(timestamp)).append(")").append(suffix);
        toStringBuff.append("  extraData=").append(toHexString(extraData)).append(suffix);
        toStringBuff.append("  minGasPrice=").append(minimumGasPrice).append(suffix);
        toStringBuff.append("  signaturesRoot=").append(toHexString(stateRoot)).append(suffix);

        return toStringBuff.toString();
    }

    public String toFlatString() {
        return toStringWithSuffix("");
    }

    // TODO added to comply with SerializableObject

    public Keccak256 getRawHash() {
        return getHash();
    }
    // TODO added to comply with SerializableObject

    public byte[] getEncodedRaw() {
        return getEncoded();
    }

    public String getShortHash() {
        return HashUtil.shortHash(getHash().getBytes());
    }

    public String getParentShortHash() {
        return HashUtil.shortHash(getParentHash().getBytes());
    }

    private static BigInteger parseBigInteger(byte[] bytes) {
        return bytes == null ? BigInteger.ZERO : BigIntegers.fromUnsignedByteArray(bytes);
    }

    public void setSignaturesRoot(byte[] signaturesRoot) {
        this.signaturesRoot = signaturesRoot;
    }

    public byte[] getSignaturesRoot() {
        return signaturesRoot;
    }
}
