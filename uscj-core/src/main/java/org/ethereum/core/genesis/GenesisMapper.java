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

package org.ethereum.core.genesis;

import co.usc.core.UscAddress;
import org.ethereum.core.Genesis;
import org.ethereum.crypto.HashUtil;
import org.ethereum.json.Utils;
import org.ethereum.util.ByteUtil;
import org.ethereum.util.RLP;

/**
 * Created by mario on 13/01/17.
 */
public class GenesisMapper {
    private static final byte[] EMPTY_LIST_HASH = HashUtil.keccak256(RLP.encodeList());

    public Genesis mapFromJson(GenesisJson json, boolean uscFormat) {
        byte[] coinbase = Utils.parseData(json.coinbase);

        byte[] timestampBytes = Utils.parseData(json.timestamp);
        long timestamp = ByteUtil.byteArrayToLong(timestampBytes);

        byte[] parentHash = Utils.parseData(json.parentHash);
        byte[] extraData = Utils.parseData(json.extraData);

        byte[] gasLimitBytes = Utils.parseData(json.gasLimit);
        long gasLimit = ByteUtil.byteArrayToLong(gasLimitBytes);

        byte[] minGasPrice = null;
        if (uscFormat) {
            minGasPrice = Utils.parseData(json.getMinimumGasPrice());
        }

        //r, s, v
        byte[] r = Utils.parseData(json.getR());

        byte[] s = Utils.parseData(json.getS());

        byte v = Utils.parseByte(json.getV());

        return new Genesis(parentHash, coinbase, Genesis.getZeroHash(),
                0, gasLimit, 0, timestamp, extraData,
                minGasPrice, r, s, v);
    }
}
