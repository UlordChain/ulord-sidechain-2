package co.usc.BpListManager;

import co.usc.core.UscAddress;
import org.ethereum.core.Repository;

import java.util.List;
import java.util.Map;

public class BlmStorageProvider {

    //Contract state keys used to store values
    private static final String BP_ADDRESS = "bpAddress";
    private static final String BP_TIME = "bpTime";

    private Repository repository;
    private UscAddress contractAddress;

    private Map<String, Long> bpList;

    public BlmStorageProvider(Repository repository, UscAddress contractAddress) {
        this.repository = repository;
        this.contractAddress = contractAddress;
    }

    public void saveBpList(Map<String, Long> bpList) {
        // TODO: Save the BP List here.
    }
}
