package co.usc.BpListManager;

import co.usc.core.UscAddress;
import org.ethereum.core.Repository;
import org.ethereum.vm.DataWord;

import java.nio.charset.StandardCharsets;

public class BlmStorageProvider {

    //Contract state keys used to store values
    private static final String BP_LIST = "bpList";

    private Repository repository;
    private UscAddress contractAddress;

    public BlmStorageProvider(Repository repository, UscAddress contractAddress) {
        this.repository = repository;
        this.contractAddress = contractAddress;
    }

    public void saveBpList(byte[] data) {
        // No need to save the data as the data can be retrieved from the transaction's data field itself.
//        DataWord address = new DataWord(BP_LIST.getBytes(StandardCharsets.UTF_8));
//        this.repository.addStorageRow(this.contractAddress, address, new DataWord(data));
    }
}
