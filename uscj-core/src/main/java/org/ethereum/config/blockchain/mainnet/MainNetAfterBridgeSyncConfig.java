package org.ethereum.config.blockchain.mainnet;

import co.usc.config.BridgeConstants;
import co.usc.config.BridgeMainNetConstants;
import org.ethereum.config.Constants;
import org.ethereum.config.blockchain.GenesisConfig;

public class MainNetAfterBridgeSyncConfig extends GenesisConfig {

    public static class MainNetConstants extends GenesisConstants {
        private static final byte CHAIN_ID = 50;

        @Override
        public BridgeConstants getBridgeConstants() {
            return BridgeMainNetConstants.getInstance();
        }

        @Override
        public int getDurationLimit() {
            return 14;
        }

        @Override
        public int getNewBlockMaxSecondsInTheFuture() {
            return 60;
        }

        @Override
        public byte getChainId() {
            return MainNetAfterBridgeSyncConfig.MainNetConstants.CHAIN_ID;
        }

    }

    public MainNetAfterBridgeSyncConfig() {
        super(new MainNetAfterBridgeSyncConfig.MainNetConstants());
    }

    protected MainNetAfterBridgeSyncConfig(Constants constants) {
        super(constants);
    }

    // Whitelisting adds unlimited option
    @Override
    public boolean isUscIP87() {return true;}

    // Improvements to REMASC contract
    @Override
    public boolean isUscIP85() {
        return true;
    }

    // Bridge local calls
    @Override
    public boolean isUscIP88() { return true; }

    // Improve blockchain block locator
    @Override
    public boolean isUscIP89() {
        return true;
    }

    // Add support for return EXTCODESIZE for precompiled contracts
    @Override
    public boolean isUscIP90() {
        return true;
    }

    // Add support for STATIC_CALL opcode
    @Override
    public boolean isUscIP91() {
        return true;
    }

    // Storage improvements
    @Override
    public boolean isUscIP92() {
        return true;
    }

    // Code Refactor, removes the sample contract
    @Override
    public boolean isUscIP93() {
        return true;
    }

    // Disable OP_CODEREPLACE
    @Override
    public boolean isUscIP94() {
        return true;
    }

    // Disable fallback mining in advance
    @Override
    public boolean isUscIP98() {
        return true;
    }
}
