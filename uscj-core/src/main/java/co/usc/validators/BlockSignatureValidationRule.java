package co.usc.validators;

import co.usc.ulordj.core.Sha256Hash;
import org.ethereum.core.Block;
import org.ethereum.crypto.ECKey;

import java.security.SignatureException;

public class BlockSignatureValidationRule implements BlockValidationRule{

    @Override
    public boolean isValid(Block block) {
        byte[] headerHash = Sha256Hash.hash(block.getHeader().getEncoded());
        return validateSignature(block.getSignature(), headerHash);
    }

    private boolean validateSignature(ECKey.ECDSASignature signature, byte[] message) {
        try {
            ECKey ecKey = ECKey.signatureToKey(message, signature.toBase64());
            //System.out.println(Hex.toHexString(ecKey.getPubKey()));
            return ECKey.verify(message, signature, ecKey.getPubKey());
        } catch (SignatureException e) {
            e.printStackTrace();
            return false;
        }
    }
}
