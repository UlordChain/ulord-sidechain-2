package co.usc.net.messages;

import org.bouncycastle.util.BigIntegers;
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
    private ECKey.ECDSASignature signature;

    public BodyResponseMessage(long id, List<Transaction> transactions, ECKey.ECDSASignature signature) {
        this.signature = signature;
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

        byte[] v = RLP.encodeByte(signature.v);
        byte[] r = RLP.encodeElement(BigIntegers.asUnsignedByteArray(signature.r));
        byte[] s = RLP.encodeElement(BigIntegers.asUnsignedByteArray(signature.s));

        return RLP.encodeList(RLP.encodeList(rlpTransactions), v, r, s);
    }

    @Override
    public MessageType getMessageType() { return MessageType.BODY_RESPONSE_MESSAGE; }

    public ECKey.ECDSASignature getSignature() {
        return signature;
    }
}
