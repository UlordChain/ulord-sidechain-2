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

package co.usc.net.handler.txvalidator;

import co.usc.BpListManager.BlmTransaction;
import co.usc.core.Coin;
import co.usc.remasc.RemascTransaction;
import org.ethereum.core.AccountState;
import org.ethereum.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.math.BigInteger;

/**
 * Checks that a transaction is not a Blm type transaction. Helps to simplify some code.
 *
 * Transaction must not be null
 */
public class TxValidatorNotBlmTxValidator implements TxValidatorStep {
    private static final Logger logger = LoggerFactory.getLogger("txvalidator");

    @Override
    public boolean validate(Transaction tx, @Nullable AccountState state, BigInteger gasLimit, Coin minimumGasPrice, long bestBlockNumber, boolean isFreeTx) {
        if (!(tx instanceof BlmTransaction)) {
            return false;
        }
        logger.warn("Invalid transaction {}: it is a Blm transaction", tx.getHash());
        return true;
    }
}
