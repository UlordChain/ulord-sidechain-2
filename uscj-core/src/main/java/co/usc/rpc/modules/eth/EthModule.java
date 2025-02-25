/*
 * This file is part of USC
 * Copyright (C) 2016 - 2018 USC developer team.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.usc.rpc.modules.eth;

import co.usc.ulordj.store.BlockStoreException;
import co.usc.config.UscSystemProperties;
import co.usc.core.ReversibleTransactionExecutor;
import co.usc.peg.BridgeState;
import co.usc.peg.BridgeSupport;
import co.usc.rpc.ExecutionBlockRetriever;
import org.ethereum.core.Block;
import org.ethereum.core.Blockchain;
import org.ethereum.core.Repository;
import org.ethereum.rpc.Web3;
import org.ethereum.rpc.converters.CallArgumentsToByteArray;
import org.ethereum.rpc.dto.CompilationResultDTO;
import org.ethereum.vm.PrecompiledContracts;
import org.ethereum.vm.program.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

import static org.ethereum.rpc.TypeConverter.toJsonHex;

// TODO add all RPC methods
@Component
public class EthModule
    implements EthModuleSolidity, EthModuleWallet {

    private static final Logger LOGGER = LoggerFactory.getLogger("web3");

    private final UscSystemProperties config;
    private final Blockchain blockchain;
    private final ReversibleTransactionExecutor reversibleTransactionExecutor;
    private final ExecutionBlockRetriever executionBlockRetriever;
    private final EthModuleSolidity ethModuleSolidity;
    private final EthModuleWallet ethModuleWallet;

    @Autowired
    public EthModule(
            UscSystemProperties config,
            Blockchain blockchain,
            ReversibleTransactionExecutor reversibleTransactionExecutor,
            ExecutionBlockRetriever executionBlockRetriever,
            EthModuleSolidity ethModuleSolidity,
            EthModuleWallet ethModuleWallet) {
        this.config = config;
        this.blockchain = blockchain;
        this.reversibleTransactionExecutor = reversibleTransactionExecutor;
        this.executionBlockRetriever = executionBlockRetriever;
        this.ethModuleSolidity = ethModuleSolidity;
        this.ethModuleWallet = ethModuleWallet;
    }

    @Override
    public String[] accounts() {
        return ethModuleWallet.accounts();
    }

    public Map<String, Object> bridgeState() throws IOException, BlockStoreException {
        Block block = blockchain.getBestBlock();
        Repository repository = blockchain.getRepository().getSnapshotTo(block.getStateRoot()).startTracking();

        BridgeSupport bridgeSupport = new BridgeSupport(
                config,
                repository,
                null,
                PrecompiledContracts.BRIDGE_ADDR,
                block,
                blockchain.getBlockStore());

        byte[] result = bridgeSupport.getStateForDebugging();

        BridgeState state = BridgeState.create(config.getBlockchainConfig().getCommonConstants().getBridgeConstants(), result);

        return state.stateToMap();
    }

    public String call(Web3.CallArguments args, String bnOrId) {
        String s = null;
        try {
            Block executionBlock = executionBlockRetriever.getExecutionBlock(bnOrId);
            ProgramResult res = callConstant(args, executionBlock);
            return s = toJsonHex(res.getHReturn());
        } finally {
            LOGGER.debug("eth_call(): {}", s);
        }
    }

    @Override
    public Map<String, CompilationResultDTO> compileSolidity(String contract) throws Exception {
        return ethModuleSolidity.compileSolidity(contract);
    }

    public String estimateGas(Web3.CallArguments args) {
        String s = null;
        try {
            ProgramResult res = callConstant(args, blockchain.getBestBlock());
            return s = toJsonHex(res.getGasUsed());
        } finally {
            LOGGER.debug("eth_estimateGas(): {}", s);
        }
    }

    @Override
    public String sendTransaction(Web3.CallArguments args) {
        return ethModuleWallet.sendTransaction(args);
    }

    @Override
    public String sign(String addr, String data) {
        return ethModuleWallet.sign(addr, data);
    }

    private ProgramResult callConstant(Web3.CallArguments args, Block executionBlock) {
        CallArgumentsToByteArray hexArgs = new CallArgumentsToByteArray(args);
        return reversibleTransactionExecutor.executeTransaction(
                executionBlock,
                executionBlock.getCoinbase(),
                hexArgs.getGasPrice(),
                hexArgs.getGasLimit(),
                hexArgs.getToAddress(),
                hexArgs.getValue(),
                hexArgs.getData(),
                hexArgs.getFromAddress()
        );
    }
}
