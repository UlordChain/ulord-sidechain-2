package org.ethereum.config.blockchain.testnet;

import co.usc.config.BridgeConstants;
import co.usc.config.BridgeTestNetConstants;
import org.ethereum.config.blockchain.GenesisConfig;

public class TestNetGenesisConfig extends GenesisConfig {

    public static class TestNetConstants extends GenesisConstants {

        private static final byte CHAIN_ID = 56;

        @Override
        public BridgeConstants getBridgeConstants() {
            return BridgeTestNetConstants.getInstance();
        }

        @Override
        public byte getChainId() {
            return TestNetConstants.CHAIN_ID;
        }

    }

    public TestNetGenesisConfig() {
        super(new TestNetConstants());
    }
}
