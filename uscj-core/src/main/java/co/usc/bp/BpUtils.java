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
import co.usc.core.Coin;
import co.usc.core.UscAddress;
import co.usc.core.bc.PendingState;
import co.usc.crypto.Keccak256;
import org.ethereum.core.TransactionPool;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;

public class BpUtils {

    private static final Logger logger = LoggerFactory.getLogger("bpserver");

    public List<org.ethereum.core.Transaction> getAllTransactions(TransactionPool transactionPool) {

        List<Transaction> txs = transactionPool.getPendingTransactions();

        return PendingState.sortByPriceTakingIntoAccountSenderAndNonce(txs);
    }

    public List<org.ethereum.core.Transaction> filterTransactions(List<Transaction> txsToRemove, List<Transaction> txs, Map<UscAddress, BigInteger> accountNonces, Repository originalRepo, Coin minGasPrice) {
        List<org.ethereum.core.Transaction> txsResult = new ArrayList<>();
        for (org.ethereum.core.Transaction tx : txs) {
            try {
                Keccak256 hash = tx.getHash();
                Coin txValue = tx.getValue();
                BigInteger txNonce = new BigInteger(1, tx.getNonce());
                UscAddress txSender = tx.getSender();
                logger.debug("Examining tx={} sender: {} value: {} nonce: {}", hash, txSender, txValue, txNonce);

                BigInteger expectedNonce;

                if (accountNonces.containsKey(txSender)) {
                    expectedNonce = accountNonces.get(txSender).add(BigInteger.ONE);
                } else {
                    expectedNonce = originalRepo.getNonce(txSender);
                }

                if (!(tx instanceof BlmTransaction) && tx.getGasPrice().compareTo(minGasPrice) < 0) {
                    logger.warn("Rejected tx={} because of low gas account {}, removing tx from pending state.", hash, txSender);

                    txsToRemove.add(tx);
                    continue;
                }

                if (!expectedNonce.equals(txNonce)) {
                    logger.warn("Invalid nonce, expected {}, found {}, tx={}", expectedNonce, txNonce, hash);
                    continue;
                }

                accountNonces.put(txSender, txNonce);

                logger.debug("Accepted tx={} sender: {} value: {} nonce: {}", hash, txSender, txValue, txNonce);
            } catch (Exception e) {
                // Txs that can't be selected by any reason should be removed from pending state
                logger.warn(String.format("Error when processing tx=%s", tx.getHash()), e);
                if (txsToRemove != null) {
                    txsToRemove.add(tx);
                } else {
                    logger.error("Can't remove invalid txs from pending state.");
                }
                continue;
            }

            txsResult.add(tx);
        }

        logger.debug("Ending getTransactions {}", txsResult.size());

        return txsResult;
    }
}
