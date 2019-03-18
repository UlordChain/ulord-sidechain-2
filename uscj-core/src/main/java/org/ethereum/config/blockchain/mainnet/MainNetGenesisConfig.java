package org.ethereum.config.blockchain.mainnet;

import co.usc.config.BridgeConstants;
import co.usc.config.BridgeMainNetConstants;
import org.ethereum.config.blockchain.GenesisConfig;

public class MainNetGenesisConfig extends GenesisConfig {

    public static class MainNetConstants extends GenesisConstants {

        private static final byte CHAIN_ID = 55;

        @Override
        public BridgeConstants getBridgeConstants() {
            return BridgeMainNetConstants.getInstance();
        }

        @Override
        public byte getChainId() {
            return MainNetConstants.CHAIN_ID;
        }

    }

    public MainNetGenesisConfig() {
        super(new MainNetConstants());
    }
}
