package co.usc.validators;

import org.ethereum.core.Block;

public class BlockBpScheduleValidationRule implements BlockValidationRule {

    @Override
    public boolean isValid(Block block) {
        // TODO: Validate if the block was generated in the scheduled time.
        return true;
    }
}
