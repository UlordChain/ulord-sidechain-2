package co.usc.validators;

import co.usc.BpListManager.BlmTransaction;
import co.usc.panic.PanicProcessor;
import org.apache.commons.collections4.CollectionUtils;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class BlmValidationRule implements BlockValidationRule {

    private static final Logger logger = LoggerFactory.getLogger("blockvalidator");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    @Override
    public boolean isValid(Block block) {
        List<Transaction> transactionsList = block.getTransactionsList();
        boolean result = CollectionUtils.isNotEmpty(transactionsList) && (transactionsList.get(transactionsList.size() - 2) instanceof BlmTransaction);
        if(!result) {
            logger.warn("Blm tx not found in block");
            panicProcessor.panic("invalidblmtx", "Blm tx not found in block");
        }
        return result;
    }
}
