package org.ethereum.config.blockchain.mainnet;

public class MainnetDisableFreeTxConfig extends MainNetGenesisConfig {
    @Override
    public boolean areBridgeTxsFree() {
        return false;
    }
}
