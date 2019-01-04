package co.usc.net.messages;

import org.ethereum.util.RLP;

public class BlockSignResponseMessage extends Message {

    String sign;
    public BlockSignResponseMessage(String sign){
        this.sign = sign;
    }

    public String getSign() {
        return this.sign;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.BLOCK_SIGN_RESPONSE_MESSAGE;
    }

    @Override
    public byte[] getEncodedMessage() {
        //TODO This function should encode the Sing of BP
        byte[] block = RLP.encode(sign);

        return RLP.encodeList(block);
    }
}
