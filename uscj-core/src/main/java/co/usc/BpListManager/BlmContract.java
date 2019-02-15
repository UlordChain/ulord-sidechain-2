package co.usc.BpListManager;

import org.ethereum.vm.PrecompiledContracts;

public class BpList extends PrecompiledContracts.PrecompiledContract {
    @Override
    public long getGasForData(byte[] data) {
        // changes here?
        return 0;
    }

    @Override
    public byte[] execute(byte[] data) {
        // Execute the contract here
        return new byte[0];
    }

    
}
