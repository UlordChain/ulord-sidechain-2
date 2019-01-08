package co.usc.validators;

import org.ethereum.core.Block;

public class BlockSignatureValidationRule implements BlockValidationRule{

    @Override
    public boolean isValid(Block block) {
        return false;
    }
}
