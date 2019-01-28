package co.usc.net.messages;

import org.ethereum.core.BlockHeader;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.util.RLP;

import java.util.List;

/**
 * Created by ajlopez on 25/08/2017.
 */
public class BodyResponseMessage extends MessageWithId {
    private long id;
    private List<Transaction> transactions;
    private byte v;
    private byte[] r;
    private byte[] s;

    public BodyResponseMessage(long id, List<Transaction> transactions, byte[] r, byte[] s, byte v) {
        this.r = r;
        this.s = s;
        this.v = v;
        this.id = id;
        this.transactions = transactions;
    }

    @Override
    public long getId() { return this.id; }

    public List<Transaction> getTransactions() { return this.transactions; }

    @Override
    protected byte[] getEncodedMessageWithoutId() {
        byte[][] rlpTransactions = new byte[this.transactions.size()][];

        for (int k = 0; k < this.transactions.size(); k++) {
            rlpTransactions[k] = this.transactions.get(k).getEncoded();
        }

        return RLP.encodeList(RLP.encodeList(rlpTransactions));
    }

    @Override
    public MessageType getMessageType() { return MessageType.BODY_RESPONSE_MESSAGE; }

    public byte getV() {
        return v;
    }

    public byte[] getR() {
        return r;
    }

    public byte[] getS() {
        return s;
    }
}
