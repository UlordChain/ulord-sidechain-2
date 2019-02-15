package co.usc.BpListManager;

import co.usc.core.UscAddress;
import org.ethereum.core.Block;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.db.BlockStore;
import org.ethereum.db.ReceiptStore;
import org.ethereum.vm.LogInfo;
import org.ethereum.vm.PrecompiledContracts;

import java.util.List;

// BP List Management Contract
public class BlmContract extends PrecompiledContracts.PrecompiledContract {

    private BmlStorageProvider provider;

    public BlmContract (UscAddress contractAddress) {
        this.contractAddress = contractAddress;
    }

    @Override
    public long getGasForData(byte[] data) {
        // changes here?
        return 0;
    }

    @Override
    public void init(Transaction executionTx, Block executionBlock, Repository repository,
                     BlockStore blockStore, ReceiptStore receiptStore, List<LogInfo> logs) {
        this.provider = new BmlStorageProvider(repository, contractAddress);
    }

    @Override
    public byte[] execute(byte[] data) {
        // Execute the contract here
        // Extract and Update BP list from data.

        provider.saveBpList();
        return new byte[0];
    }
}
