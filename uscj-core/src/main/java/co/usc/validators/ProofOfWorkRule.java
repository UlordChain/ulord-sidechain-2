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

package co.usc.validators;

import co.usc.ulordj.core.UldBlock;
import co.usc.ulordj.core.Sha256Hash;
import co.usc.config.BridgeConstants;
import co.usc.config.UscMiningConstants;
import co.usc.config.UscSystemProperties;
import co.usc.util.DifficultyUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.Pack;
import org.ethereum.config.BlockchainNetConfig;
import org.ethereum.config.Constants;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.crypto.ECKey;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPElement;
import org.ethereum.util.RLPList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Checks proof value against its boundary for the block header.
 */
@Component
public class ProofOfWorkRule implements BlockHeaderValidationRule, BlockValidationRule {

    private static final Logger logger = LoggerFactory.getLogger("blockvalidator");
    private static final BigInteger SECP256K1N_HALF = Constants.getSECP256K1N().divide(BigInteger.valueOf(2));

    private final UscSystemProperties config;
    private final BlockchainNetConfig blockchainConfig;
    private final BridgeConstants bridgeConstants;
    private final Constants constants;

    @Autowired
    public ProofOfWorkRule(UscSystemProperties config) {
        this.config = config;
        this.blockchainConfig = config.getBlockchainConfig();
        this.bridgeConstants = blockchainConfig.getCommonConstants().getBridgeConstants();
        this.constants = blockchainConfig.getCommonConstants();
    }


    @Override
    public boolean isValid(Block block) {
        return isValid(block.getHeader());
    }

    @Override
    public boolean isValid(BlockHeader header) {
        // TODO: refactor this an move it to another class. Change the Global ProofOfWorkRule to AuthenticationRule.
        // TODO: Make ProofOfWorkRule one of the classes that inherits from AuthenticationRule.
        /*
        co.usc.ulordj.core.NetworkParameters ulordNetworkParameters = bridgeConstants.getUldParams();
        MerkleProofValidator mpValidator;
        try {
            if (blockchainConfig.getConfigForBlock(header.getNumber()).isUscIP92()) {
                //mpValidator = new UscIP92MerkleProofValidator(header.getUlordMergedMiningMerkleProof());
            } else {
                //mpValidator = new GenesisMerkleProofValidator(ulordNetworkParameters, header.getUlordMergedMiningMerkleProof());
            }
        } catch (RuntimeException ex) {
            logger.warn("Merkle proof can't be validated. Header {}", header.getShortHash(), ex);
            return false;
	    }

        byte[] ulordMergedMiningCoinbaseTransactionCompressed = header.getUlordMergedMiningCoinbaseTransaction();

        if (ulordMergedMiningCoinbaseTransactionCompressed == null) {
            logger.warn("Compressed coinbase transaction does not exist. Header {}", header.getShortHash());
            return false;
        }

        if (header.getUlordMergedMiningHeader() == null) {
            logger.warn("Ulord merged mining header does not exist. Header {}", header.getShortHash());
            return false;
        }

        BigInteger ulordMergedMiningBlockHashBI = ulordMergedMiningBlock.getHash().toBigInteger();

        if (ulordMergedMiningBlockHashBI.compareTo(target) > 0) {
            logger.warn("Hash {} is higher than target {}", ulordMergedMiningBlockHashBI.toString(16), target.toString(16));
            return false;
        }

        byte[] ulordMergedMiningCoinbaseTransactionMidstate = new byte[UscMiningConstants.MIDSTATE_SIZE];
        System.arraycopy(ulordMergedMiningCoinbaseTransactionCompressed, 0, ulordMergedMiningCoinbaseTransactionMidstate, 8, UscMiningConstants.MIDSTATE_SIZE_TRIMMED);

        byte[] ulordMergedMiningCoinbaseTransactionTail = new byte[ulordMergedMiningCoinbaseTransactionCompressed.length - UscMiningConstants.MIDSTATE_SIZE_TRIMMED];
        System.arraycopy(ulordMergedMiningCoinbaseTransactionCompressed, UscMiningConstants.MIDSTATE_SIZE_TRIMMED,
                ulordMergedMiningCoinbaseTransactionTail, 0, ulordMergedMiningCoinbaseTransactionTail.length);

        byte[] expectedCoinbaseMessageBytes = org.bouncycastle.util.Arrays.concatenate(UscMiningConstants.USC_TAG, header.getHashForMergedMining());


        List<Byte> ulordMergedMiningCoinbaseTransactionTailAsList = Arrays.asList(ArrayUtils.toObject(ulordMergedMiningCoinbaseTransactionTail));
        List<Byte> expectedCoinbaseMessageBytesAsList = Arrays.asList(ArrayUtils.toObject(expectedCoinbaseMessageBytes));

        int uscTagPosition = Collections.lastIndexOfSubList(ulordMergedMiningCoinbaseTransactionTailAsList, expectedCoinbaseMessageBytesAsList);
        if (uscTagPosition == -1) {
            logger.warn("ulord coinbase transaction tail message does not contain expected USCBLOCK:UscBlockHeaderHash. Expected: {} . Actual: {} .", Arrays.toString(expectedCoinbaseMessageBytes), Arrays.toString(ulordMergedMiningCoinbaseTransactionTail));
            return false;
        }
        */

        /*
        * We check that the there is no other block before the usc tag, to avoid a possible malleability attack:
        * If we have a mid state with 10 blocks, and the usc tag, we can also have
        * another mid state with 9 blocks, 64bytes + the usc tag, giving us two blocks with different hashes but the same spv proof.
        * */

        /*
        if (uscTagPosition >= 64) {
            logger.warn("ulord coinbase transaction tag position is bigger than expected 64. Actual: {}.", Integer.toString(uscTagPosition));
            return false;
        }

        List<Byte> uscTagAsList = Arrays.asList(ArrayUtils.toObject(UscMiningConstants.USC_TAG));
        int lastTag = Collections.lastIndexOfSubList(ulordMergedMiningCoinbaseTransactionTailAsList, uscTagAsList);
        if (uscTagPosition !=lastTag) {
            logger.warn("The valid USC tag is not the last USC tag. Tail: {}.", Arrays.toString(ulordMergedMiningCoinbaseTransactionTail));
            return false;
        }

        int remainingByteCount = ulordMergedMiningCoinbaseTransactionTail.length -
                uscTagPosition -
                UscMiningConstants.USC_TAG.length -
                UscMiningConstants.BLOCK_HEADER_HASH_SIZE;

        if (remainingByteCount > UscMiningConstants.MAX_BYTES_AFTER_MERGED_MINING_HASH) {
            logger.warn("More than 128 bytes after USC tag");
            return false;
        }

        // TODO test
        long byteCount = Pack.bigEndianToLong(ulordMergedMiningCoinbaseTransactionMidstate, 8);
        long coinbaseLength = ulordMergedMiningCoinbaseTransactionTail.length + byteCount;
        if (coinbaseLength <= 64) {
            logger.warn("Coinbase transaction must always be greater than 64-bytes long. But it was: {}", coinbaseLength);
            return false;
        }

        SHA256Digest digest = new SHA256Digest(ulordMergedMiningCoinbaseTransactionMidstate);
        digest.update(ulordMergedMiningCoinbaseTransactionTail,0,ulordMergedMiningCoinbaseTransactionTail.length);
        byte[] ulordMergedMiningCoinbaseTransactionOneRoundOfHash = new byte[32];
        digest.doFinal(ulordMergedMiningCoinbaseTransactionOneRoundOfHash, 0);
        Sha256Hash ulordMergedMiningCoinbaseTransactionHash = Sha256Hash.wrapReversed(Sha256Hash.hash(ulordMergedMiningCoinbaseTransactionOneRoundOfHash));

        if (!mpValidator.isValid(ulordMergedMiningBlock.getMerkleRoot(), ulordMergedMiningCoinbaseTransactionHash)) {
            logger.warn("ulord merkle branch doesn't match coinbase and state root");
            return false;
        }

        */
        return true;
    }
}
