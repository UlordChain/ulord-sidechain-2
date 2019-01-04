package org.ethereum.config.blockchain.mainnet;

public class MainNetShakespeareConfig extends MainNetAfterBridgeSyncConfig {

    @Override
    public boolean areBridgeTxsFree() {
        return true;
    }

}
