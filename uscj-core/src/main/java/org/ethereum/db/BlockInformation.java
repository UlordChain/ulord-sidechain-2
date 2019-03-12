package org.ethereum.db;

//import co.usc.core.BlockDifficulty;

/**
 * Created by usuario on 07/06/2017.
 */
public class BlockInformation {
    private byte[] hash;
    private boolean inMainChain;

    public BlockInformation(byte[] hash, boolean inMainChain) {
        this.hash = hash;
        this.inMainChain = inMainChain;
    }

    public byte[] getHash() {
        return this.hash;
    }

    public boolean isInMainChain() {
        return this.inMainChain;
    }
}
