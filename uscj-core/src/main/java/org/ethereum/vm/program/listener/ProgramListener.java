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

package org.ethereum.vm.program.listener;

import org.ethereum.vm.DataWord;

public interface ProgramListener {

    void onMemoryExtend(int delta);

    void onMemoryWrite(int address, byte[] data, int size);

    void onStackPop();

    void onStackPush(DataWord value);

    void onStackSwap(int from, int to);

    void onStoragePut(DataWord key, DataWord value);

    void onStoragePut(DataWord key, byte[] value);

    void onStorageClear();
}