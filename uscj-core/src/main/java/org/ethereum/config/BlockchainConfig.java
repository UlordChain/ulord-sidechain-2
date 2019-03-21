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

package org.ethereum.config;

import org.ethereum.core.BlockHeader;

/**
 * Describes constants and algorithms used for a specific blockchain at specific stage
 *
 * Created by Anton Nashatyrev on 25.02.2016.
 */
public interface BlockchainConfig {

    /**
     * Get blockchain constants
     */
    Constants getConstants();

    boolean areBridgeTxsFree();

    // Improvements to REMASC contract
    boolean isUscIP85();

    // Whitelisting adds unlimited option
    boolean isUscIP87();

    // Bridge local calls
    boolean isUscIP88();

    // Improve blockchain block locator
    boolean isUscIP89();

    // Add support for return EXTCODESIZE for precompiled contracts
    boolean isUscIP90();

    // Add support for STATIC_CALL opcode
    boolean isUscIP91();

    // Storage improvements
    boolean isUscIP92();

    // Code Refactor, removes the sample contract
    boolean isUscIP93();

    // Disable OP_CODEREPLACE
    boolean isUscIP94();

    // Disable fallback mining in advance
    boolean isUscIP98();

    // Disable SPV Proofs
    boolean isUscIP99();
}
