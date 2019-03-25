package co.usc.validators;

import co.usc.BpListManager.BlmTransaction;
import co.usc.core.UscAddress;
import co.usc.panic.PanicProcessor;
import org.apache.commons.collections4.CollectionUtils;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.ethereum.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class BlmValidationRule implements BlockValidationRule {
    private static final byte[] ONE_BYTE_ARRAY = new byte[]{1};

    private static final Logger logger = LoggerFactory.getLogger("blockvalidator");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    @Override
    public boolean isValid(Block block) {
        List<Transaction> transactionsList = block.getTransactionsList();
        Transaction tx = transactionsList.get(transactionsList.size() - 2);
        boolean result = CollectionUtils.isNotEmpty(transactionsList) && (tx instanceof BlmTransaction);
        if(!result) {
            logger.warn("Blm tx not found in block");
            panicProcessor.panic("invalidblmtx", "Blm tx not found in block");
        }

        UscAddress sender = tx.getSender();
        if(!sender.toString().equals(getBlmOneAddr().toString())) {
            logger.warn("Blm tx: Invalid sender address");
            panicProcessor.panic("invalidblmtx", "Blm tx: Invalid sender address");
            result = false;
        }

        if(tx.getData() == null || !isValidData(tx.getData())) {
            logger.warn("Blm tx: Invalid data or BPList");
            panicProcessor.panic("invalidblmtx", "Blm tx: Invalid data or BPList.");
            result = false;
        }

        return result;
    }
    private UscAddress getBlmOneAddr() {
        return new UscAddress(new byte[20]) {
            @Override
            public byte[] getBytes() {
                return ONE_BYTE_ARRAY;
            }
        };
    }

    private boolean isValidData(byte[] data) {
        try {
            Utils.decodeBpList(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
