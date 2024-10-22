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
import org.bouncycastle.util.encoders.Hex;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds Merkle proofs with the format used since Genesis until RSKIP 92 activation
 */
public class GenesisMerkleProofBuilder implements MerkleProofBuilder {

    @Override
    public byte[] buildFromMerkleHashes(
            UldBlock blockWithHeaderOnly,
            List<String> merkleHashesString,
            int blockTxnCount) {
        List<Sha256Hash> merkleHashes = merkleHashesString.stream()
                .map(mk -> Sha256Hash.wrapReversed(Hex.decode(mk)))
                .collect(Collectors.toList());
        int merkleTreeHeight = (int) Math.ceil(Math.log(blockTxnCount) / Math.log(2));

        // bitlist will always have ones at the beginning because merkle branch is built for coinbase tx
        List<Boolean> bitList = new ArrayList<>();
        for (int i = 0; i < merkleHashes.size() + merkleTreeHeight; i++) {
            bitList.add(i < merkleHashes.size());
        }

        // bits indicates which nodes are going to be used for building the partial merkle tree
        // for more information please refer to {@link co.usc.ulordj.core.PartialMerkleTree#buildFromLeaves } method
        byte[] bits = new byte[(bitList.size() + 7) / 8];
        for (int i = 0; i < bitList.size(); i++) {
            if (bitList.get(i)) {
                Utils.setBitLE(bits, i);
            }
        }

        PartialMerkleTree bitcoinMergedMiningMerkleBranch = new PartialMerkleTree(blockWithHeaderOnly.getParams(), bits, merkleHashes, blockTxnCount);

        return bitcoinMergedMiningMerkleBranch.ulordSerialize();
    }

    @Override
    public byte[] buildFromTxHashes(
            UldBlock blockWithHeaderOnly,
            List<String> txHashesString) {
        List<Sha256Hash> txHashes = txHashesString.stream().map(Sha256Hash::wrap).collect(Collectors.toList());

        PartialMerkleTree bitcoinMergedMiningMerkleBranch = getBitcoinMergedMerkleBranch(txHashes, blockWithHeaderOnly.getParams());

        return bitcoinMergedMiningMerkleBranch.ulordSerialize();
    }

    @Override
    public byte[] buildFromBlock(UldBlock ulordMergedMiningBlock) {
        List<Sha256Hash> txHashes = ulordMergedMiningBlock.getTransactions().stream()
                .map(UldTransaction::getHash)
                .collect(Collectors.toList());

        PartialMerkleTree ulordMergedMiningMerkleBranch = getBitcoinMergedMerkleBranch(txHashes, ulordMergedMiningBlock.getParams());

        return ulordMergedMiningMerkleBranch.ulordSerialize();
    }

    /**
     * getBitcoinMergedMerkleBranch returns the Partial Merkle Branch needed to validate that the coinbase tx
     * is part of the Merkle Tree.
     *
     * @param txHashes the ulord txs that were included in a block.
     * @return A Partial Merkle Branch in which you can validate the coinbase tx.
     */
    private static PartialMerkleTree getBitcoinMergedMerkleBranch(List<Sha256Hash> txHashes, NetworkParameters params) {
        /*
           We need to convert the txs to a bitvector to choose which ones
           will be included in the Partial Merkle Tree.

           We need txs.size() / 8 bytes to represent this vector.
           The coinbase tx is the first one of the txs so we set the first bit to 1.
         */
        byte[] bitvector = new byte[(txHashes.size() + 7) / 8];
        Utils.setBitLE(bitvector, 0);
        return PartialMerkleTree.buildFromLeaves(params, bitvector, txHashes);
    }
}
