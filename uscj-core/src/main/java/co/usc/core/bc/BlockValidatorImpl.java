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
import co.usc.config.UscSystemProperties;
import co.usc.validators.BlockParentDependantValidationRule;
import co.usc.validators.BlockValidationRule;
import co.usc.validators.BlockValidator;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.Constants;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.db.BlockStore;
import org.ethereum.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BlockValidator has methods to validate block content before its execution
 *
 * Created by ajlopez on 29/07/2016.
 */
@Component
public class BlockValidatorImpl implements BlockValidator {

    private static final Logger logger = LoggerFactory.getLogger("blockchain");

    private BlockStore blockStore;

    private BlockParentDependantValidationRule blockParentValidator;

    private BlockValidationRule blockValidator;

    @Autowired
    public BlockValidatorImpl(BlockStore blockStore, BlockParentDependantValidationRule blockParentValidator,
                              @Qualifier("blockValidationRule") BlockValidationRule blockValidator) {
        this.blockStore = blockStore;
        this.blockParentValidator = blockParentValidator;
        this.blockValidator = blockValidator;
    }

    /**
     * Validate a block.
     * The validation includes
     * - Validate the header data relative to parent block
     * - Validate the transaction root hash to transaction list
     * - Validate transactions
     * - Validate signature
     * - Validate BP and Time if not syncing
     *
     * @param block        Block to validate
     * @return true if the block is valid, false if the block is invalid
     */
    @Override
    public boolean isValid(Block block) {
        if (block.isGenesis()) {
            return true;
        }

        Block parent = getParent(block);

        if(!this.blockParentValidator.isValid(block, parent)) {
            return false;
        }

        if(!this.blockValidator.isValid(block)) {
            return false;
        }

        // We accept 1st blocks BP List.
        if (parent.isGenesis()) {
            return true;
        }

        // Validate BP here.
        return validateBpAndSchedule(block, parent);
    }

    private boolean validateBpAndSchedule(Block block, Block parent) {
        long blockTime = block.getTimestamp();

        Transaction blmTransaction = getBlmTransaction(block);
        if(blmTransaction == null) {
            logger.warn("The block must contain at lease one BlmTransaction");
            return false;
        }

        try {
            List<String> bpList = Utils.decodeBpList(blmTransaction.getData());

            // Validate if same producer produced blocks in different time slots or rounds.
            if(block.getCoinbase().equals(parent.getCoinbase())) {
                long parentTimestamp = parent.getTimestamp();
                long timestamp = block.getTimestamp();
                long diff = timestamp - parentTimestamp;
                long gap = (Constants.getBlockIntervalMs() * (long)Constants.getProducerRepetitions() * (bpList.size() - 1)) / 1000;
                if((diff < gap) && !getParent(parent).isGenesis()) {
                    logger.warn("Invalid Block: Blocks are from the same round.");
                    return false;
                }
            }

            // Validate if the block is produced by one of the BPs
            boolean isBp = false;
            int thisBpIndex = -1;
            for (String pubKey : bpList) {
                thisBpIndex++;
                String currentProducerAddress = block.getCoinbase().toString();
                String producerAddress = Hex.toHexString(ECKey.fromPublicOnly(Hex.decode(pubKey)).getAddress());
                isBp = currentProducerAddress.equals(producerAddress);
                if(isBp)
                    break;
            }
            if(!isBp) {
                logger.warn("The producer of this block is not an active BP");
                return false;
            }

            // Check if this producer produced block in his given time slot
            return checkValidTime(blockTime, bpList, thisBpIndex);

        } catch (Exception e) {
            logger.warn("Error Decoding BPList of block: " + block.getNumber());
            return false;
        }
    }

    private boolean checkValidTime(long blockTime, List<String> bpList, int valBpIndex) {
        long blockTimestampC = Constants.getBlockTimestampEpoch();
        long blockInterval = Constants.getBlockIntervalMs();
        int producerRepetitions = Constants.getProducerRepetitions();
        int bpScheduledIndex = Utils.getBpScheduledIndex(blockTime * 1000, blockTimestampC, blockInterval, producerRepetitions, bpList.size());
        return bpList.get(bpScheduledIndex).equals(bpList.get(valBpIndex));
    }

    private Transaction getBlmTransaction(Block block) {
        for (Transaction tx : block.getTransactionsList()) {
            if(tx instanceof BlmTransaction) {
                return tx;
            }
        }
        return null;
    }

    private Block getParent(Block block) {
        if (this.blockStore == null) {
            return null;
        }

        return blockStore.getBlockByHash(block.getParentHash().getBytes());
    }
}

