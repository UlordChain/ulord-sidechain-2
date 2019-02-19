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
    private static final byte[] ZERO_BYTE_ARRAY = new byte[]{0};

    private BlmStorageProvider provider;
    private Transaction executionTx;
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
        this.provider = new BlmStorageProvider(repository, contractAddress);
        this.executionTx = executionTx;
    }

    @Override
    public byte[] execute(byte[] data) {
        UscAddress sender = executionTx.getSender();
        if(!sender.toString().equals(getSender().toString()) || data == null) {
            return new byte[0];
        }

        provider.saveBpList(data);
        return new byte[0];
    }

    private UscAddress getSender() {
        return new UscAddress(new byte[20]) {
            @Override
            public byte[] getBytes() {
                return ZERO_BYTE_ARRAY;
            }
        };
    }
}
