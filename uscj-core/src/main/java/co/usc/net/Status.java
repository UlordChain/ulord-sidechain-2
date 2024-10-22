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

package co.usc.net;

import javax.annotation.Nullable;

/**
 * Created by ajlopez on 5/11/2016.
 */
public class Status {
    private final long bestBlockNumber;
    private final byte[] bestBlockHash;
    private final byte[] bestBlockParentHash;

    public Status(long bestBlockNumber, byte[] bestBlockHash) {
        this(bestBlockNumber, bestBlockHash, null);
    }

    public Status(long bestBlockNumber, byte[] bestBlockHash, byte[] bestBlockParentHash) {
        this.bestBlockNumber = bestBlockNumber;
        this.bestBlockHash = bestBlockHash;
        this.bestBlockParentHash = bestBlockParentHash;
    }

    public long getBestBlockNumber() {
        return this.bestBlockNumber;
    }

    public byte[] getBestBlockHash() {
        return this.bestBlockHash;
    }

    @Nullable
    public byte[] getBestBlockParentHash() { return this.bestBlockParentHash; }

}
