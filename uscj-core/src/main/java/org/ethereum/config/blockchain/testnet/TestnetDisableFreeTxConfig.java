package org.ethereum.config.blockchain.testnet;

public class TestnetDisableFreeTxConfig extends TestNetGenesisConfig {
    @Override
    public boolean areBridgeTxsFree() {
        return false;
    }
}
