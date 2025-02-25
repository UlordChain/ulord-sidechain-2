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

package org.ethereum.net.p2p;

import org.bouncycastle.util.encoders.Hex;

/**
 * Wrapper around an Ethereum GetPeers message on the network
 *
 * @see org.ethereum.net.p2p.P2pMessageCodes#GET_PEERS
 */
public class GetPeersMessage extends P2pMessage {

    /**
     * GetPeers message is always a the same single command payload
     */
    private static final byte[] FIXED_PAYLOAD = Hex.decode("C104");

    @Override
    public byte[] getEncoded() {
        return FIXED_PAYLOAD;
    }

    @Override
    public P2pMessageCodes getCommand() {
        return P2pMessageCodes.GET_PEERS;
    }

    @Override
    public Class<PeersMessage> getAnswerMessage() {
        return null;
    }

    @Override
    public String toString() {
        return "[" + this.getCommand().name() + "]";
    }
}