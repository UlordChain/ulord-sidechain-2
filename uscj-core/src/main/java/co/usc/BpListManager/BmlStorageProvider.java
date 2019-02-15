package co.usc.BpListManager;

import co.usc.core.UscAddress;
import org.ethereum.core.Repository;

import java.util.List;

public class BmlStorageProvider {

    //Contract state keys used to store values
    private static final String BP_ADDRESS = "bpAddress";
    private static final String BP_TIME = "bpTime";

    private Repository repository;
    private UscAddress contractAddress;

    private List<String> bpAddress;
    private List<String> bpTime;

    public BmlStorageProvider(Repository repository, UscAddress contractAddress) {
        this.repository = repository;
        this.contractAddress = contractAddress;
    }

    public void saveBpList() {
        // TODO: Save the BP List here.
    }
}
