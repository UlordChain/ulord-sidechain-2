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

package co.usc.net.messages;

import com.google.common.annotations.VisibleForTesting;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPElement;
import org.ethereum.util.RLPList;

import java.util.ArrayList;

/**
 * Created by ajlopez on 5/10/2016.
 */
public abstract class Message {

    public abstract MessageType getMessageType();

    public abstract byte[] getEncodedMessage();

    public final byte[] getEncoded() {
        byte[] type = RLP.encodeByte(getMessageType().getTypeAsByte());
        byte[] body = RLP.encodeElement(this.getEncodedMessage());
        return RLP.encodeList(type, body);
    }

    @VisibleForTesting
    static Message create(byte[] encoded) {
        return create((RLPList) RLP.decode2(encoded).get(0));
    }

    public static Message create(ArrayList<RLPElement> paramsList) {
        byte[] body = paramsList.get(1).getRLPData();

        if (body != null) {
            int type = paramsList.get(0).getRLPData()[0];
            MessageType messageType = MessageType.valueOfType(type);
            RLPList list = (RLPList) RLP.decode2(body).get(0);
            return messageType.createMessage(list);

        }
        return null;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + getMessageType() +
                '}';
    }
}
