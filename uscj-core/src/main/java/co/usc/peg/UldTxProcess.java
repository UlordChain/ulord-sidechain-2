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

package co.usc.peg;

import co.usc.crypto.Keccak256;
import org.ethereum.crypto.HashUtil;

import java.util.*;

/**
 * Immutable representation of an USC Pending Federation.
 * A pending federation is one that is being actively
 * voted by the current federation to potentially become
 * the new active federation.
 *
 * @author Ariel Mendelzon
 */
public final class UldTxProcess {
    private List<byte[]> uldTxHashes;

    public UldTxProcess(List<byte[]> uldTxHashes) {
        this.uldTxHashes = uldTxHashes;
    }

    public List<byte[]> getUldTxHashes() {
        return uldTxHashes;
    }

    /**
     * Creates a new PendingFederation with the additional specified public key
     * @param uldTxSerialized the new public key
     * @return a new PendingFederation with the added public key
     */
    public UldTxProcess addUldTx(byte[] uldTxSerialized) {
        List<byte[]> newHashes = new ArrayList<>(uldTxHashes);
        newHashes.add(uldTxSerialized);
        return new UldTxProcess(newHashes);
    }


    public Keccak256 getHash() {
        byte[] encoded = BridgeSerializationUtils.serializeUldTxHashProcess(this);
        return new Keccak256(HashUtil.keccak256(encoded));
    }

    @Override
    public int hashCode() {
        // Can use java.util.Objects.hash since List<UldECKey> has a
        // well-defined hashCode()
        return Objects.hash(getUldTxHashes());
    }
}
