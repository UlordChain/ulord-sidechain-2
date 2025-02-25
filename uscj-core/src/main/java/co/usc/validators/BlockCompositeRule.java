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

package co.usc.validators;

import org.ethereum.core.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by mario on 18/01/17.
 */
public class BlockCompositeRule implements BlockValidationRule {

    private static final Logger logger = LoggerFactory.getLogger("blockvalidator");

    private List<BlockValidationRule> rules;

    public BlockCompositeRule(BlockValidationRule... rules) {
        this.rules = new ArrayList<>();
        if(rules != null) {
            for (BlockValidationRule rule : rules) {
                if(rule != null) {
                    this.rules.add(rule);
                }
            }
        }
    }
    @Override
    public boolean isValid(Block block) {
        String shortHash = block.getShortHash();
        long number = block.getNumber();
        logger.debug("Validating block {} {}", shortHash, number);
        for(BlockValidationRule rule : this.rules) {
            if(!rule.isValid(block)) {
                logger.warn("Error Validating block {} {}", shortHash, number);
                return false;
            }
        }
        return true;
    }
}
