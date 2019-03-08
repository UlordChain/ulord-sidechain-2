package co.usc.rpc.modules.uos;

import org.springframework.stereotype.Component;
//TODO: this Module is used by UOS to propose block on USC-2 and other functions ex. validate BP list.
@Component
public class UosModuleImpl implements UosModule {
    @Override
    public void pushBPList(String list, String signedList) {
        //TODO: This RPC function is intended to get a list of BP's from UOS.
    }
}
