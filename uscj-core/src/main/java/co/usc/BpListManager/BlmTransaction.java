package co.usc.BpListManager;

import co.usc.core.UscAddress;
import org.ethereum.config.BlockchainNetConfig;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.ethereum.util.ByteUtil;
import org.ethereum.vm.PrecompiledContracts;

public class BlmTransaction extends Transaction {
    private static final byte[] ZERO_BYTE_ARRAY = new byte[]{0};
    private static final byte[] ONE_BYTE_ARRAY = new byte[]{1};


    public BlmTransaction(byte[] rawData) {
        super(rawData);
    }

    public BlmTransaction(long blockNumber, byte[] bpListData) {
        super(ByteUtil.longToBytesNoLeadZeroes(
                blockNumber - 1),
                ZERO_BYTE_ARRAY,
                ZERO_BYTE_ARRAY,
                PrecompiledContracts.BP_LIST_ADDR.getBytes(),
                ZERO_BYTE_ARRAY,
                bpListData,
                (byte) 0);
    }

    @Override
    public long transactionCost(Block block, BlockchainNetConfig netConfig) {
        // BlmTransaction does not pay any fees
        return 0;
    }

    @Override
    public synchronized UscAddress getSender() {
        return new UscAddress(new byte[20]) {
            @Override
            public byte[] getBytes() {
                return ONE_BYTE_ARRAY;
            }
        };
    }

    @Override
    public boolean acceptTransactionSignature(byte chainId) {
        // Does not require signature validation.
        return true;
    }
}
