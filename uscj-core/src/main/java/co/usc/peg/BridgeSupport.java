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

package co.usc.peg;

import co.usc.ulordj.core.*;
import co.usc.ulordj.crypto.TransactionSignature;
import co.usc.ulordj.script.Script;
import co.usc.ulordj.script.ScriptBuilder;
import co.usc.ulordj.script.ScriptChunk;
import co.usc.ulordj.store.BlockStoreException;
import co.usc.ulordj.wallet.SendRequest;
import co.usc.ulordj.wallet.Wallet;
import co.usc.config.BridgeConstants;
import co.usc.config.UscSystemProperties;
import co.usc.core.UscAddress;
import co.usc.crypto.Keccak256;
import co.usc.panic.PanicProcessor;
import co.usc.peg.whitelist.LockWhitelist;
import co.usc.peg.whitelist.LockWhitelistEntry;
import co.usc.peg.whitelist.OneOffWhiteListEntry;
import co.usc.peg.whitelist.UnlimitedWhiteListEntry;
import co.usc.peg.utils.BridgeEventLogger;
import co.usc.peg.utils.UldTransactionFormatUtils;
import co.usc.peg.utils.PartialMerkleTreeFormatUtils;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.core.Block;
import org.ethereum.core.Blockchain;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.db.BlockStore;
import org.ethereum.vm.PrecompiledContracts;
import org.ethereum.vm.program.Program;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.util.encoders.Hex;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Helper class to move funds from uld to usc and usc to uld
 * @author Oscar Guindzberg
 */
public class BridgeSupport {
    public static final int MAX_RELEASE_ITERATIONS = 30;

    public static final Integer FEDERATION_CHANGE_GENERIC_ERROR_CODE = -10;
    public static final Integer LOCK_WHITELIST_GENERIC_ERROR_CODE = -10;
    public static final Integer LOCK_WHITELIST_INVALID_ADDRESS_FORMAT_ERROR_CODE = -2;
    public static final Integer LOCK_WHITELIST_ALREADY_EXISTS_ERROR_CODE = -1;
    public static final Integer LOCK_WHITELIST_UNKNOWN_ERROR_CODE = 0;
    public static final Integer LOCK_WHITELIST_SUCCESS_CODE = 1;
    public static final Integer FEE_PER_KB_GENERIC_ERROR_CODE = -10;
    public static final Integer ULD_TX_PROCESS_GENERIC_ERROR_CODE = -10;

    private static final Logger logger = LoggerFactory.getLogger("BridgeSupport");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private static final String INVALID_ADDRESS_FORMAT_MESSAGE = "invalid address format";

    private final List<String> FEDERATION_CHANGE_FUNCTIONS = Collections.unmodifiableList(Arrays.asList(
            "create",
            "add",
            "commit",
            "rollback"));

    private final BridgeConstants bridgeConstants;
    private final BridgeStorageProvider provider;
    private final Repository uscRepository;
    private final UscSystemProperties config;
    private final BridgeEventLogger eventLogger;

    private final FederationSupport federationSupport;

    private final Context uldContext;
    private UldBlockstoreWithCache uldBlockStore;
    private UldBlockChain uldBlockChain;
    private final org.ethereum.core.Block uscExecutionBlock;

    private BlockStore blockStore;
    // Used by unit tests
    public BridgeSupport(
            UscSystemProperties config,
            Repository repository,
            BridgeEventLogger eventLogger,
            BridgeConstants bridgeConstants,
            BridgeStorageProvider provider,
            UldBlockstoreWithCache uldBlockStore,
            UldBlockChain uldBlockChain,
            Block executionBlock) {
        this(
                repository,
                provider,
                executionBlock,
                config,
                bridgeConstants,
                eventLogger,
                new Context(bridgeConstants.getUldParams()),
                uldBlockStore,
                uldBlockChain
        );
    }

    // Used by bridge
    public BridgeSupport(
            UscSystemProperties config,
            Repository repository,
            BridgeEventLogger eventLogger,
            UscAddress contractAddress,
            Block uscExecutionBlock,
            BlockStore blockStore) {
        this(
                repository,
                new BridgeStorageProvider(
                        repository,
                        contractAddress,
                        config.getBlockchainConfig().getCommonConstants().getBridgeConstants(),
                        BridgeStorageConfiguration.fromBlockchainConfig(config.getBlockchainConfig().getConfigForBlock(uscExecutionBlock.getNumber()))
                ),
                uscExecutionBlock,
                config,
                config.getBlockchainConfig().getCommonConstants().getBridgeConstants(),
                eventLogger,
                new Context(config.getBlockchainConfig().getCommonConstants().getBridgeConstants().getUldParams()),
                null,
                null
        );
        this.blockStore = blockStore;
    }

    public BridgeSupport(
            UscSystemProperties config,
            Repository repository,
            BridgeEventLogger eventLogger,
            BridgeStorageProvider provider,
            Block uscExecutionBlock) {
        this(
                repository,
                provider,
                uscExecutionBlock,
                config,
                config.getBlockchainConfig().getCommonConstants().getBridgeConstants(),
                eventLogger,
                new Context(config.getBlockchainConfig().getCommonConstants().getBridgeConstants().getUldParams()),
                null,
                null
        );
    }

    private BridgeSupport(
            Repository repository,
            BridgeStorageProvider provider,
            Block executionBlock,
            UscSystemProperties config,
            BridgeConstants bridgeConstants,
            BridgeEventLogger eventLogger,
            Context uldContext,
            UldBlockstoreWithCache uldBlockStore,
            UldBlockChain uldBlockChain) {
        this(
                repository,
                provider,
                executionBlock,
                config,
                bridgeConstants,
                eventLogger,
                uldContext,
                new FederationSupport(provider, bridgeConstants, executionBlock),
                uldBlockStore,
                uldBlockChain
        );
    }

    public BridgeSupport(
            Repository repository,
            BridgeStorageProvider provider,
            Block executionBlock,
            UscSystemProperties config,
            BridgeConstants bridgeConstants,
            BridgeEventLogger eventLogger,
            Context uldContext,
            FederationSupport federationSupport,
            UldBlockstoreWithCache uldBlockStore,
            UldBlockChain uldBlockChain) {
        this.uscRepository = repository;
        this.provider = provider;
        this.uscExecutionBlock = executionBlock;
        this.config = config;
        this.bridgeConstants = bridgeConstants;
        this.eventLogger = eventLogger;
        this.uldContext = uldContext;
        this.federationSupport = federationSupport;
        this.uldBlockStore = uldBlockStore;
        this.uldBlockChain = uldBlockChain;
    }

    private RepositoryBlockStore buildRepositoryBlockStore() throws BlockStoreException, IOException {
        NetworkParameters uldParams = this.bridgeConstants.getUldParams();
        RepositoryBlockStore uldBlockStore = new RepositoryBlockStore(
                this.config,
                this.uscRepository,
                PrecompiledContracts.BRIDGE_ADDR
        );
        if (uldBlockStore.getChainHead().getHeader().getHash().equals(uldParams.getGenesisBlock().getHash())) {
            // We are building the blockstore for the first time, so we have not set the checkpoints yet.
            long time = federationSupport.getActiveFederation().getCreationTime().toEpochMilli();
            InputStream checkpoints = this.getCheckPoints();
            if (time > 0 && checkpoints != null) {
                CheckpointManager.checkpoint(uldParams, checkpoints, uldBlockStore, time);
            }
        }
        return uldBlockStore;
    }

    @VisibleForTesting
    InputStream getCheckPoints() {
        InputStream checkpoints = BridgeSupport.class.getResourceAsStream("/usculordcheckpoints/" + bridgeConstants.getUldParams().getId() + ".checkpoints");
        if (checkpoints == null) {
            // If we don't have a custom checkpoints file, try to use ulordj's default checkpoints for that network
            checkpoints = BridgeSupport.class.getResourceAsStream("/" + bridgeConstants.getUldParams().getId() + ".checkpoints");
        }
        return checkpoints;
    }

    public void save() throws IOException {
        provider.save();
    }

    /**
     * Receives an array of serialized Ulord block headers and adds them to the internal BlockChain structure.
     * @param headers The ulord headers
     */
    public void receiveHeaders(UldBlock[] headers) throws IOException {
        if (headers.length > 0) {
            logger.debug("Received {} headers. First {}, last {}.", headers.length, headers[0].getHash(), headers[headers.length - 1].getHash());
        } else {
            logger.warn("Received 0 headers");
        }

        Context.propagate(uldContext);
        this.ensureUldBlockChain();
        for (int i = 0; i < headers.length; i++) {
            try {
                uldBlockChain.add(headers[i]);
            } catch (Exception e) {
                // If we tray to add an orphan header ulordj throws an exception
                // This catches that case and any other exception that may be thrown
                logger.warn("Exception adding uld header", e);
            }
        }
    }

    /**
     * Get the wallet for the currently active federation
     * @return A ULD wallet for the currently active federation
     *
     * @throws IOException
     */
    public Wallet getActiveFederationWallet() throws IOException {
        Federation federation = getActiveFederation();
        List<UTXO> utxos = getActiveFederationUldUTXOs();

        return BridgeUtils.getFederationSpendWallet(uldContext, federation, utxos);
    }

    /**
     * Get the wallet for the currently retiring federation
     * or null if there's currently no retiring federation
     * @return A ULD wallet for the currently active federation
     *
     * @throws IOException
     */
    public Wallet getRetiringFederationWallet() throws IOException {
        Federation federation = getRetiringFederation();
        if (federation == null) {
            return null;
        }

        List<UTXO> utxos = getRetiringFederationUldUTXOs();

        return BridgeUtils.getFederationSpendWallet(uldContext, federation, utxos);
    }

    /**
     * Get the wallet for the currently live federations
     * but limited to a specific list of UTXOs
     * @return A ULD wallet for the currently live federation(s)
     * limited to the given list of UTXOs
     *
     * @throws IOException
     */
    public Wallet getUTXOBasedWalletForLiveFederations(List<UTXO> utxos) throws IOException {
        return BridgeUtils.getFederationsSpendWallet(uldContext, getLiveFederations(), utxos);
    }

    /**
     * Get a no spend wallet for the currently live federations
     * @return A no spend ULD wallet for the currently live federation(s)
     *
     * @throws IOException
     */
    public Wallet getNoSpendWalletForLiveFederations() throws IOException {
        return BridgeUtils.getFederationsNoSpendWallet(uldContext, getLiveFederations());
    }

    /**
     * In case of a lock tx: Transfers some SULDs to the sender of the uld tx and keeps track of the new UTXOs available for spending.
     * In case of a release tx: Keeps track of the change UTXOs, now available for spending.
     * @param uscTx The USC transaction
     * @param uldTxSerialized The raw ULD tx
     * @param height The height of the ULD block that contains the tx
     * @param pmtSerialized The raw partial Merkle tree
     * @throws BlockStoreException
     * @throws IOException
     */
    public void registerUldTransaction(Transaction uscTx, byte[] uldTxSerialized, int height, byte[] pmtSerialized) throws IOException, BlockStoreException {
        Context.propagate(uldContext);

        Sha256Hash uldTxHash = UldTransactionFormatUtils.calculateUldTxHash(uldTxSerialized);
        // Check the tx was not already processed
        if (provider.getUldTxHashesAlreadyProcessed().keySet().contains(uldTxHash)) {
            logger.warn("Supplied tx was already processed");
            return;
        }

        if (!PartialMerkleTreeFormatUtils.hasExpectedSize(pmtSerialized)) {
            throw new BridgeIllegalArgumentException("PartialMerkleTree doesn't have expected size");
        }

        Sha256Hash merkleRoot;
        try {
            PartialMerkleTree pmt = new PartialMerkleTree(bridgeConstants.getUldParams(), pmtSerialized, 0);
            List<Sha256Hash> hashesInPmt = new ArrayList<>();
            merkleRoot = pmt.getTxnHashAndMerkleRoot(hashesInPmt);
            if (!hashesInPmt.contains(uldTxHash)) {
                logger.warn("Supplied tx is not in the supplied partial merkle tree");
                return;
            }
        } catch (VerificationException e) {
            throw new BridgeIllegalArgumentException("PartialMerkleTree could not be parsed " + Hex.toHexString(pmtSerialized), e);
        }

        if (height < 0) {
            String panicMessage = String.format("Height is %d but should be greater than 0", height);
            logger.warn(panicMessage);
            panicProcessor.panic("uldlock", panicMessage);
            return;
        }

        // Check there are at least N blocks on top of the supplied height
        int uldBestChainHeight = getUldBlockchainBestChainHeight();
        int confirmations = uldBestChainHeight - height + 1;
        if (confirmations < bridgeConstants.getUld2UscMinimumAcceptableConfirmations()) {
            logger.warn(
                    "At least {} confirmations are required, but there are only {} confirmations",
                    bridgeConstants.getUld2UscMinimumAcceptableConfirmations(),
                    confirmations
            );
            return;
        }

        if (UldTransactionFormatUtils.getInputsCount(uldTxSerialized) == 0) {
            logger.warn("Tx {} has no inputs ", uldTxHash);
            // this is the exception thrown by co.usc.ulordj.core.UldTransaction#verify when there are no inputs.
            throw new VerificationException.EmptyInputsOrOutputs();
        }

        // Check the the merkle root equals merkle root of uld block at specified height in the uld best chain
        // ULD blockstore is available since we've already queried the best chain height
        UldBlock blockHeader = BridgeUtils.getStoredBlockAtHeight(uldBlockStore, height).getHeader();
        if (!blockHeader.getMerkleRoot().equals(merkleRoot)) {
            String panicMessage = String.format(
                    "Supplied merkle root %s does not match block's merkle root %s",
                    merkleRoot,
                    blockHeader.getMerkleRoot()
            );
            logger.warn(panicMessage);
            panicProcessor.panic("uldlock", panicMessage);
            return;
        }

        UldTransaction uldTx = new UldTransaction(bridgeConstants.getUldParams(), uldTxSerialized);
        uldTx.verify();

        boolean locked = true;

        Federation activeFederation = getActiveFederation();
        // Specific code for lock/release/none txs
        if (BridgeUtils.isLockTx(uldTx, getLiveFederations(), uldContext, bridgeConstants)) {
            logger.debug("This is a lock tx {}", uldTx);
            Optional<Script> scriptSig = BridgeUtils.getFirstInputScriptSig(uldTx);
            if (!scriptSig.isPresent()) {
                logger.warn(
                        "[uldtx:{}] First input does not spend a Pay-to-PubkeyHash {}",
                        uldTx.getHash(),
                        uldTx.getInput(0)
                );
                return;
            }

            // Compute the total amount sent. Value could have been sent both to the
            // currently active federation as well as to the currently retiring federation.
            // Add both amounts up in that case.
            Coin amountToActive = uldTx.getValueSentToMe(getActiveFederationWallet());
            Coin amountToRetiring = Coin.ZERO;
            Wallet retiringFederationWallet = getRetiringFederationWallet();
            if (retiringFederationWallet != null) {
                amountToRetiring = uldTx.getValueSentToMe(retiringFederationWallet);
            }
            Coin totalAmount = amountToActive.add(amountToRetiring);

            // Get the sender public key
            byte[] data = scriptSig.get().getChunks().get(1).data;

            // Tx is a lock tx, check whether the sender is whitelisted
            UldECKey senderUldKey = UldECKey.fromPublicOnly(data);
            Address senderUldAddress = new Address(uldContext.getParams(), senderUldKey.getPubKeyHash());

            // If the address is not whitelisted, then return the funds
            // using the exact same utxos sent to us.
            // That is, build a release transaction and get it in the release transaction set.
            // Otherwise, transfer SULD to the sender of the ULD
            // The USC account to update is the one that matches the pubkey "spent" on the first ulord tx input
            LockWhitelist lockWhitelist = provider.getLockWhitelist();
            if (!lockWhitelist.isWhitelistedFor(senderUldAddress, totalAmount, height)) {
                locked = false;
                // Build the list of UTXOs in the ULD transaction sent to either the active
                // or retiring federation
                List<UTXO> utxosToUs = uldTx.getWalletOutputs(getNoSpendWalletForLiveFederations()).stream()
                        .map(output ->
                                new UTXO(
                                        uldTx.getHash(),
                                        output.getIndex(),
                                        output.getValue(),
                                        0,
                                        uldTx.isCoinBase(),
                                        output.getScriptPubKey()
                                )
                        ).collect(Collectors.toList());
                // Use the list of UTXOs to build a transaction builder
                // for the return uld transaction generation
                ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
                        uldContext.getParams(),
                        getUTXOBasedWalletForLiveFederations(utxosToUs),
                        senderUldAddress,
                        getFeePerKb()
                );
                Optional<ReleaseTransactionBuilder.BuildResult> buildReturnResult = txBuilder.buildEmptyWalletTo(senderUldAddress);
                if (buildReturnResult.isPresent()) {
                    provider.getReleaseTransactionSet().add(buildReturnResult.get().getUldTx(), uscExecutionBlock.getNumber());
                    logger.info("whitelist money return tx build successful to {}. Tx {}. Value {}.", senderUldAddress, uscTx, totalAmount);
                } else {
                    logger.warn("whitelist money return tx build for uld tx {} error. Return was to {}. Tx {}. Value {}", uldTx.getHash(), senderUldAddress, uscTx, totalAmount);
                    panicProcessor.panic("whitelist-return-funds", String.format("whitelist money return tx build for uld tx {} error. Return was to {}. Tx {}. Value {}", uldTx.getHash(), senderUldAddress, uscTx, totalAmount));
                }
            } else {
                org.ethereum.crypto.ECKey key = org.ethereum.crypto.ECKey.fromPublicOnly(data);
                UscAddress sender = new UscAddress(key.getAddress());

                uscRepository.transfer(
                        PrecompiledContracts.BRIDGE_ADDR,
                        sender,
                        co.usc.core.Coin.fromUlord(totalAmount)
                );
                // Consume this whitelisted address
                lockWhitelist.consume(senderUldAddress);
            }
        } else if (BridgeUtils.isReleaseTx(uldTx, getLiveFederations())) {
            logger.debug("This is a release tx {}", uldTx);
            // do-nothing
            // We could call removeUsedUTXOs(uldTx) here, but we decided to not do that.
            // Used utxos should had been removed when we created the release tx.
            // Invoking removeUsedUTXOs() here would make "some" sense in theses scenarios:
            // a) In testnet, devnet or local: we restart the USC blockchain whithout changing the federation address. We don't want to have utxos that were already spent.
            // Open problem: TxA spends TxB. registerUldTransaction() for TxB is called, it spends a utxo the bridge is not yet aware of,
            // so nothing is removed. Then registerUldTransaction() for TxA and the "already spent" utxo is added as it was not spent.
            // When is not guaranteed to be called in the chronological order, so a Federator can inform
            // b) In prod: Federator created a tx manually or the federation was compromised and some utxos were spent. Better not try to spend them.
            // Open problem: For performance removeUsedUTXOs() just removes 1 utxo
        } else if (BridgeUtils.isMigrationTx(uldTx, activeFederation, getRetiringFederation(), uldContext, bridgeConstants)) {
            logger.debug("This is a migration tx {}", uldTx);
        } else {
            logger.warn("This is not a lock, a release nor a migration tx {}", uldTx);
            panicProcessor.panic("uldlock", "This is not a lock, a release nor a migration tx " + uldTx);
            return;
        }

        // Mark tx as processed on this block
        provider.getUldTxHashesAlreadyProcessed().put(uldTxHash, uscExecutionBlock.getNumber());

        // Save UTXOs from the federation(s) only if we actually
        // locked the funds.
        if (locked) {
            saveNewUTXOs(uldTx);
        }
        logger.info("ULD Tx {} processed in USC", uldTxHash);
    }

    /*
      Add the uldTx outputs that send uld to the federation(s) to the UTXO list
     */
    private void saveNewUTXOs(UldTransaction uldTx) throws IOException {
        // Outputs to the active federation
        List<TransactionOutput> outputsToTheActiveFederation = uldTx.getWalletOutputs(getActiveFederationWallet());
        for (TransactionOutput output : outputsToTheActiveFederation) {
            UTXO utxo = new UTXO(uldTx.getHash(), output.getIndex(), output.getValue(), 0, uldTx.isCoinBase(), output.getScriptPubKey());
            getActiveFederationUldUTXOs().add(utxo);
        }

        // Outputs to the retiring federation (if any)
        Wallet retiringFederationWallet = getRetiringFederationWallet();
        if (retiringFederationWallet != null) {
            List<TransactionOutput> outputsToTheRetiringFederation = uldTx.getWalletOutputs(retiringFederationWallet);
            for (TransactionOutput output : outputsToTheRetiringFederation) {
                UTXO utxo = new UTXO(uldTx.getHash(), output.getIndex(), output.getValue(), 0, uldTx.isCoinBase(), output.getScriptPubKey());
                getRetiringFederationUldUTXOs().add(utxo);
            }
        }
    }


    public void registerUldTransactionByVote(Transaction uscTx, byte[] uldTxSerialized, int height)
            throws IOException, BlockStoreException {

        Context.propagate(uldContext);

        Sha256Hash uldTxHash = UldTransactionFormatUtils.calculateUldTxHash(uldTxSerialized);

        // Check the tx was not already processed
        if (provider.getUldTxHashesAlreadyProcessed().keySet().contains(uldTxHash)) {
            logger.warn("Supplied tx was already processed");
            return;
        }

        UldTransaction uldTx = new UldTransaction(bridgeConstants.getUldParams(), uldTxSerialized);
        uldTx.verify();

        boolean locked = true;

        Federation activeFederation = getActiveFederation();

        // Specific code for lock/release/none txs
        if (BridgeUtils.isLockTx(uldTx, getLiveFederations(), uldContext, bridgeConstants)) {
            logger.debug("This is a lock tx {}", uldTx);
            Optional<Script> scriptSig = BridgeUtils.getFirstInputScriptSig(uldTx);
            if (!scriptSig.isPresent()) {
                logger.warn(
                        "[uldtx:{}] First input does not spend a Pay-to-PubkeyHash {}",
                        uldTx.getHash(),
                        uldTx.getInput(0)
                );
                return;
            }

            // vote the ulord transaction for release sut
            ABICallSpec winnerSpec = voteLockUt(uscTx, uldTxSerialized);
            if (null == winnerSpec) {
                return;
            }
            byte[] winnerUldTxSerialized = (byte[])winnerSpec.getArguments()[0];
            if (!Arrays.equals(winnerUldTxSerialized, uldTxSerialized)) {
                return;
            }

            // Compute the total amount sent. Value could have been sent both to the
            // currently active federation as well as to the currently retiring federation.
            // Add both amounts up in that case.
            Coin amountToActive = uldTx.getValueSentToMe(getActiveFederationWallet());
            Coin amountToRetiring = Coin.ZERO;
            Wallet retiringFederationWallet = getRetiringFederationWallet();
            if (retiringFederationWallet != null) {
                amountToRetiring = uldTx.getValueSentToMe(retiringFederationWallet);
            }
            Coin totalAmount = amountToActive.add(amountToRetiring);

            // Get the sender public key
            byte[] data = scriptSig.get().getChunks().get(1).data;
            UldECKey senderUldKey = UldECKey.fromPublicOnly(data);
            Address senderUldAddress = new Address(uldContext.getParams(), senderUldKey.getPubKeyHash());

            // Tx is a lock tx, check whether the sender is whitelisted, If the address is not whitelisted, then return the funds
            // using the exact same utxos sent to us.
            // That is, build a release transaction and get it in the release transaction set.
            // Otherwise, transfer SULD to the sender of the ULD
            // The USC account to update is the one that matches the pubkey "spent" on the first ulord tx input
            LockWhitelist lockWhitelist = provider.getLockWhitelist();
            if (!lockWhitelist.isWhitelistedFor(senderUldAddress, totalAmount, height)) {
                locked = false;
                // Build the list of UTXOs in the ULD transaction sent to either the active
                // or retiring federation
                UldTransaction finalUldTx = uldTx;
                List<UTXO> utxosToUs = uldTx.getWalletOutputs(getNoSpendWalletForLiveFederations()).stream()
                        .map(output ->
                            new UTXO (
                                finalUldTx.getHash(),
                                output.getIndex(),
                                output.getValue(),
                                0,
                                finalUldTx.isCoinBase(),
                                output.getScriptPubKey()
                            )
                        ).collect(Collectors.toList());
                // Use the list of UTXOs to build a transaction builder
                // for the return uld transaction generation
                ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
                        uldContext.getParams(),
                        getUTXOBasedWalletForLiveFederations(utxosToUs),
                        senderUldAddress,
                        getFeePerKb()
                );
                Optional<ReleaseTransactionBuilder.BuildResult> buildReturnResult = txBuilder.buildEmptyWalletTo(senderUldAddress);
                if (buildReturnResult.isPresent()) {
                    provider.getReleaseTransactionSet().add(buildReturnResult.get().getUldTx(), uscExecutionBlock.getNumber());
                    logger.info("whitelist money return tx build successful to {}. Tx {}. Value {}.", senderUldAddress, uscTx, totalAmount);
                } else {
                    logger.warn("whitelist money return tx build for uld tx {} error. Return was to {}. Tx {}. Value {}", uldTx.getHash(), senderUldAddress, uscTx, totalAmount);
                    panicProcessor.panic("whitelist-return-funds", String.format("whitelist money return tx build for uld tx {} error. Return was to {}. Tx {}. Value {}", uldTx.getHash(), senderUldAddress, uscTx, totalAmount));
                }
            } else {
                org.ethereum.crypto.ECKey key = org.ethereum.crypto.ECKey.fromPublicOnly(data);
                UscAddress sender = new UscAddress(key.getAddress());

                uscRepository.transfer(
                        PrecompiledContracts.BRIDGE_ADDR,
                        sender,
                        co.usc.core.Coin.fromUlord(totalAmount)
                );
                // Consume this whitelisted address
                lockWhitelist.consume(senderUldAddress);
            }
        } else if (BridgeUtils.isReleaseTx(uldTx, getLiveFederations())) {
            logger.debug("This is a release tx {}", uldTx);
            // do-nothing
            // We could call removeUsedUTXOs(uldTx) here, but we decided to not do that.
            // Used utxos should had been removed when we created the release tx.
            // Invoking removeUsedUTXOs() here would make "some" sense in theses scenarios:
            // a) In testnet, devnet or local: we restart the USC blockchain whithout changing the federation address. We don't want to have utxos that were already spent.
            // Open problem: TxA spends TxB. registerUldTransaction() for TxB is called, it spends a utxo the bridge is not yet aware of,
            // so nothing is removed. Then registerUldTransaction() for TxA and the "already spent" utxo is added as it was not spent.
            // When is not guaranteed to be called in the chronological order, so a Federator can inform
            // b) In prod: Federator created a tx manually or the federation was compromised and some utxos were spent. Better not try to spend them.
            // Open problem: For performance removeUsedUTXOs() just removes 1 utxo
        } else if (BridgeUtils.isMigrationTx(uldTx, activeFederation, getRetiringFederation(), uldContext, bridgeConstants)) {
            logger.debug("This is a migration tx {}", uldTx);
        } else {
            logger.warn("This is not a lock, a release nor a migration tx {}", uldTx);
            panicProcessor.panic("uldlock", "This is not a lock, a release nor a migration tx " + uldTx);
            return;
        }

        // Mark tx as processed on this block
        provider.getUldTxHashesAlreadyProcessed().put(uldTxHash, uscExecutionBlock.getNumber());

        // Save UTXOs from the federation(s) only if we actually
        // locked the funds.
        if (locked) {
            saveNewUTXOs(uldTx);
        }

        logger.info("ULD Tx {} processed in USC", uldTxHash);
    }

    /**
     *
     * @throws IOException
     */
    private ABICallSpec voteLockUt(Transaction uscTx, byte[] uldTxSerialized) throws IOException {
        // Must be authorized to vote (checking for signature)
        AddressBasedAuthorizer authorizer = federationSupport.getActiveFederationAuthorizer();
        if (!authorizer.isAuthorized(uscTx)) {
            logger.error("Sender address authorize failed");
            return null;
        }

        ABICallElection election = provider.getLockUtProcessElection(authorizer);

        ABICallSpec callSpec = new ABICallSpec("voteLockUt", new byte[][]{uldTxSerialized});
        // Register the vote. It is expected to succeed, since all previous checks succeeded
        if (!election.vote(callSpec, uscTx.getSender())) {
            return null;
        }

        // If enough votes have been reached, then actually execute the function
        ABICallSpec winnerSpec = election.getWinner();
        if (winnerSpec != null) {
            // Clear the winner so that we don't repeat ourselves
            election.clearWinners(winnerSpec);
            return winnerSpec;
        }

        return null;
    }

    public String getPendingUldTxForVote(){
        AddressBasedAuthorizer authorizer = federationSupport.getActiveFederationAuthorizer();
        if (null == authorizer){
            logger.error("address authorize is null");
            return null;
        }

        JSONArray jsonArray = new JSONArray();
        Map<String, List<String>> mapUldTxIdVoters = new HashMap<String, List<String>>();
        ABICallElection election = provider.getLockUtProcessElection(authorizer);
        for (Map.Entry<ABICallSpec, List<UscAddress>> specVotes : election.getVotes().entrySet()) {
            Sha256Hash uldTxHash = UldTransactionFormatUtils.calculateUldTxHash((byte[])specVotes.getKey().getArguments()[0]);
            List<String> listVoters = specVotes.getValue().stream().map(UscAddress::toString).collect(Collectors.toList());
            mapUldTxIdVoters.put(Sha256Hash.bytesToHex(uldTxHash.getBytes()), listVoters);
        }
        jsonArray.put(mapUldTxIdVoters);

        return jsonArray.toString();
    }

    /**
     * Initiates the process of sending coins back to ULD.
     * This is the default contract method.
     * The funds will be sent to the ulord address controlled by the private key that signed the usc tx.
     * The amount sent to the bridge in this tx will be the amount sent in the uld network minus fees.
     * @param uscTx The usc tx being executed.
     * @throws IOException
     */
    public void releaseUld(Transaction uscTx) throws IOException {

        //as we can't send uld from contracts we want to send them back to the sender
        if (BridgeUtils.isContractTx(uscTx)) {
            logger.trace("Contract {} tried to release funds. Release is just allowed from standard accounts.", uscTx);
            throw new Program.OutOfGasException("Contract calling releaseULD");
        }

        Context.propagate(uldContext);
        NetworkParameters uldParams = bridgeConstants.getUldParams();
        Address uldDestinationAddress = BridgeUtils.recoverUldAddressFromEthTransaction(uscTx, uldParams);
        Coin value = uscTx.getValue().toUlord();
        boolean addResult = requestRelease(uldDestinationAddress, value);

        if (addResult) {
            logger.info("releaseUld succesful to {}. Tx {}. Value {}.", uldDestinationAddress, uscTx, value);
        } else {
            logger.warn("releaseUld ignored because value is considered dust. To {}. Tx {}. Value {}.", uldDestinationAddress, uscTx, value);
        }
    }

    /**
     * Creates a request for ULD release and
     * adds it to the request queue for it
     * to be processed later.
     *
     * @param destinationAddress the destination ULD address.
     * @param value the amount of ULD to release.
     * @return true if the request was successfully added, false if the value to release was
     * considered dust and therefore ignored.
     * @throws IOException
     */
    private boolean requestRelease(Address destinationAddress, Coin value) throws IOException {
        if (!value.isGreaterThan(bridgeConstants.getMinimumReleaseTxValue())) {
            return false;
        }

        provider.getReleaseRequestQueue().add(destinationAddress, value);

        return true;
    }

    /**
     * @return Current fee per kb in ULD.
     */
    public Coin getFeePerKb() {
        Coin currentFeePerKb = provider.getFeePerKb();

        if (currentFeePerKb == null) {
            currentFeePerKb = bridgeConstants.getGenesisFeePerKb();
        }

        return currentFeePerKb;
    }

    /**
     * Executed every now and then.
     * Performs a few tasks: processing of any pending uld funds
     * migrations from retiring federations;
     * processing of any outstanding uld release requests; and
     * processing of any outstanding release uld transactions.
     * @throws IOException
     * @param uscTx current USC transaction
     */
    public void updateCollections(Transaction uscTx) throws IOException {
        Context.propagate(uldContext);

        eventLogger.logUpdateCollections(uscTx);

        processFundsMigration();

        processReleaseRequests();

        processReleaseTransactions(uscTx);
    }

    private boolean federationIsInMigrationAge(Federation federation) {
        long federationAge = uscExecutionBlock.getNumber() - federation.getCreationBlockNumber();
        long ageBegin = bridgeConstants.getFederationActivationAge() + bridgeConstants.getFundsMigrationAgeSinceActivationBegin();
        long ageEnd = bridgeConstants.getFederationActivationAge() + bridgeConstants.getFundsMigrationAgeSinceActivationEnd();

        return federationAge > ageBegin && federationAge < ageEnd;
    }

    private boolean federationIsPastMigrationAge(Federation federation) {
        long federationAge = uscExecutionBlock.getNumber() - federation.getCreationBlockNumber();
        long ageEnd = bridgeConstants.getFederationActivationAge() + bridgeConstants.getFundsMigrationAgeSinceActivationEnd();

        return federationAge >= ageEnd;
    }

    private boolean hasMinimumFundsToMigrate(@Nullable Wallet retiringFederationWallet) {
        // This value is set according to the average 500 bytes transaction size
        Coin minimumFundsToMigrate = getFeePerKb().divide(2);
        return retiringFederationWallet != null
                && retiringFederationWallet.getBalance().isGreaterThan(minimumFundsToMigrate);
    }

    private void processFundsMigration() throws IOException {
        Wallet retiringFederationWallet = getRetiringFederationWallet();
        List<UTXO> availableUTXOs = getRetiringFederationUldUTXOs();
        ReleaseTransactionSet releaseTransactionSet = provider.getReleaseTransactionSet();
        Federation activeFederation = getActiveFederation();

        if (federationIsInMigrationAge(activeFederation)
                && hasMinimumFundsToMigrate(retiringFederationWallet)) {
            logger.info("Active federation (age={}) is in migration age and retiring federation has funds to migrate: {}.",
                    uscExecutionBlock.getNumber() - activeFederation.getCreationBlockNumber(),
                    retiringFederationWallet.getBalance().toFriendlyString());

            Pair<UldTransaction, List<UTXO>> createResult = createMigrationTransaction(retiringFederationWallet, activeFederation.getAddress());
            UldTransaction uldTx = createResult.getLeft();
            List<UTXO> selectedUTXOs = createResult.getRight();

            // Add the TX to the release set
            releaseTransactionSet.add(uldTx, uscExecutionBlock.getNumber());

            // Mark UTXOs as spent
            availableUTXOs.removeIf(utxo -> selectedUTXOs.stream().anyMatch(selectedUtxo ->
                    utxo.getHash().equals(selectedUtxo.getHash()) &&
                            utxo.getIndex() == selectedUtxo.getIndex()
            ));
        }

        if (retiringFederationWallet != null && federationIsPastMigrationAge(activeFederation)) {
            if (retiringFederationWallet.getBalance().isGreaterThan(Coin.ZERO)) {
                logger.info("Federation is past migration age and will try to migrate remaining balance: {}.",
                        retiringFederationWallet.getBalance().toFriendlyString());

                try {
                    Pair<UldTransaction, List<UTXO>> createResult = createMigrationTransaction(retiringFederationWallet, activeFederation.getAddress());
                    UldTransaction uldTx = createResult.getLeft();
                    List<UTXO> selectedUTXOs = createResult.getRight();

                    // Add the TX to the release set
                    releaseTransactionSet.add(uldTx, uscExecutionBlock.getNumber());

                    // Mark UTXOs as spent
                    availableUTXOs.removeIf(utxo -> selectedUTXOs.stream().anyMatch(selectedUtxo ->
                            utxo.getHash().equals(selectedUtxo.getHash()) &&
                                    utxo.getIndex() == selectedUtxo.getIndex()
                    ));
                } catch (Exception e) {
                    logger.error("Unable to complete retiring federation migration. Balance left: {} in {}",
                            retiringFederationWallet.getBalance().toFriendlyString(),
                            getRetiringFederationAddress());
                    panicProcessor.panic("updateCollection", "Unable to complete retiring federation migration.");
                }
            }

            logger.info("Retiring federation migration finished. Available UTXOs left: {}.", availableUTXOs.size());
            provider.setOldFederation(null);
        }
    }

    /**
     * Processes the current uld release request queue
     * and tries to build uld transactions using (and marking as spent)
     * the current active federation's utxos.
     * Newly created uld transactions are added to the uld release tx set,
     * and failed attempts are kept in the release queue for future
     * processing.
     *
     */
    private void processReleaseRequests() {
        final Wallet activeFederationWallet;
        final ReleaseRequestQueue releaseRequestQueue;

        try {
            activeFederationWallet = getActiveFederationWallet();
            releaseRequestQueue = provider.getReleaseRequestQueue();
        } catch (IOException e) {
            logger.error("Unexpected error accessing storage while attempting to process release requests", e);
            return;
        }

        // Releases are attempted using the currently active federation
        // wallet.
        final ReleaseTransactionBuilder txBuilder = new ReleaseTransactionBuilder(
                uldContext.getParams(),
                activeFederationWallet,
                getFederationAddress(),
                getFeePerKb()
        );

        releaseRequestQueue.process(MAX_RELEASE_ITERATIONS, (ReleaseRequestQueue.Entry releaseRequest) -> {
            Optional<ReleaseTransactionBuilder.BuildResult> result = txBuilder.buildAmountTo(
                    releaseRequest.getDestination(),
                    releaseRequest.getAmount()
            );

            // Couldn't build a transaction to release these funds
            // Log the event and return false so that the request remains in the
            // queue for future processing.
            // Further logging is done at the tx builder level.
            if (!result.isPresent()) {
                logger.warn(
                        "Couldn't build a release ULD tx for <{}, {}>",
                        releaseRequest.getDestination().toBase58(),
                        releaseRequest.getAmount().toString()
                );
                return false;
            }

            // We have a ULD transaction, mark the UTXOs as spent and add the tx
            // to the release set.

            List<UTXO> selectedUTXOs = result.get().getSelectedUTXOs();
            UldTransaction generatedTransaction = result.get().getUldTx();
            List<UTXO> availableUTXOs;
            ReleaseTransactionSet releaseTransactionSet;

            // Attempt access to storage first
            // (any of these could fail and would invalidate both
            // the tx build and utxo selection, so treat as atomic)
            try {
                availableUTXOs = getActiveFederationUldUTXOs();
                releaseTransactionSet = provider.getReleaseTransactionSet();
            } catch (IOException exception) {
                // Unexpected error accessing storage, log and fail
                logger.error(
                        String.format(
                                "Unexpected error accessing storage while attempting to add a release ULD tx for <%s, %s>",
                                releaseRequest.getDestination().toString(),
                                releaseRequest.getAmount().toString()
                        ),
                        exception
                );
                return false;
            }

            // Add the TX
            releaseTransactionSet.add(generatedTransaction, uscExecutionBlock.getNumber());

            // Mark UTXOs as spent
            availableUTXOs.removeAll(selectedUTXOs);

            // TODO: (Ariel Mendelzon, 07/12/2017)
            // TODO: Balance adjustment assumes that change output is output with index 1.
            // TODO: This will change if we implement multiple releases per ULD tx, so
            // TODO: it would eventually need to be fixed.
            // Adjust balances in edge cases
            adjustBalancesIfChangeOutputWasDust(generatedTransaction, releaseRequest.getAmount());

            return true;
        });
    }

    /**
     * Processes the current uld release transaction set.
     * It basically looks for transactions with enough confirmations
     * and marks them as ready for signing as well as removes them
     * from the set.
     * @param uscTx the USC transaction that is causing this processing.
     */
    private void processReleaseTransactions(Transaction uscTx) {
        final Map<Keccak256, UldTransaction> txsWaitingForSignatures;
        final ReleaseTransactionSet releaseTransactionSet;

        try {
            txsWaitingForSignatures = provider.getUscTxsWaitingForSignatures();
            releaseTransactionSet = provider.getReleaseTransactionSet();
        } catch (IOException e) {
            logger.error("Unexpected error accessing storage while attempting to process release uld transactions", e);
            return;
        }

        // TODO: (Ariel Mendelzon - 07/12/2017)
        // TODO: at the moment, there can only be one uld transaction
        // TODO: per usc transaction in the txsWaitingForSignatures
        // TODO: map, and the rest of the processing logic is
        // TODO: dependant upon this. That is the reason we
        // TODO: add only one uld transaction at a time
        // TODO: (at least at this stage).

        Set<UldTransaction> txsWithEnoughConfirmations = releaseTransactionSet.sliceWithIrreversibility(Optional.of(1), blockStore);

        // Add the uld transaction to the 'awaiting signatures' list
        if (txsWithEnoughConfirmations.size() > 0) {
            txsWaitingForSignatures.put(uscTx.getHash(), txsWithEnoughConfirmations.iterator().next());
        }
    }

    /**
     * If federation change output value had to be increased to be non-dust, the federation now has
     * more ULD than it should. So, we burn some sULD to make balances match.
     *
     * @param uldTx      The uld tx that was just completed
     * @param sentByUser The number of sULD originaly sent by the user
     */
    private void adjustBalancesIfChangeOutputWasDust(UldTransaction uldTx, Coin sentByUser) {
        if (uldTx.getOutputs().size() <= 1) {
            // If there is no change, do-nothing
            return;
        }
        Coin sumInputs = Coin.ZERO;
        for (TransactionInput transactionInput : uldTx.getInputs()) {
            sumInputs = sumInputs.add(transactionInput.getValue());
        }
        Coin change = uldTx.getOutput(1).getValue();
        Coin spentByFederation = sumInputs.subtract(change);
        if (spentByFederation.isLessThan(sentByUser)) {
            Coin coinsToBurn = sentByUser.subtract(spentByFederation);
            UscAddress burnAddress = config.getBlockchainConfig().getCommonConstants().getBurnAddress();
            uscRepository.transfer(PrecompiledContracts.BRIDGE_ADDR, burnAddress, co.usc.core.Coin.fromUlord(coinsToBurn));
        }
    }

    /**
     * Adds a federator bpSignature to a uld release tx.
     * The hash for the bpSignature must be calculated with Transaction.SigHash.ALL and anyoneCanPay=false. The bpSignature must be canonical.
     * If enough signatures were added, ask federators to broadcast the uld release tx.
     *
     * @param federatorPublicKey   Federator who is signing
     * @param signatures           1 bpSignature per uld tx input
     * @param uscTxHash            The id of the usc tx
     */
    public void addSignature(UldECKey federatorPublicKey, List<byte[]> signatures, byte[] uscTxHash) throws Exception {
        Context.propagate(uldContext);
        Federation retiringFederation = getRetiringFederation();
        if (!getActiveFederation().getPublicKeys().contains(federatorPublicKey) && (retiringFederation == null || !retiringFederation.getPublicKeys().contains(federatorPublicKey))) {
            logger.warn("Supplied federator public key {} does not belong to any of the federators.", federatorPublicKey);
            return;
        }
        UldTransaction uldTx = provider.getUscTxsWaitingForSignatures().get(new Keccak256(uscTxHash));
        if (uldTx == null) {
            logger.warn("No tx waiting for bpSignature for hash {}. Probably fully signed already.", new Keccak256(uscTxHash));
            return;
        }
        if (uldTx.getInputs().size() != signatures.size()) {
            logger.warn("Expected {} signatures but received {}.", uldTx.getInputs().size(), signatures.size());
            return;
        }
        eventLogger.logAddSignature(federatorPublicKey, uldTx, uscTxHash);
        processSigning(federatorPublicKey, signatures, uscTxHash, uldTx);
    }

    private void processSigning(UldECKey federatorPublicKey, List<byte[]> signatures, byte[] uscTxHash, UldTransaction uldTx) throws IOException {
        // Build input hashes for signatures
        int numInputs = uldTx.getInputs().size();

        List<Sha256Hash> sighashes = new ArrayList<>();
        List<TransactionSignature> txSigs = new ArrayList<>();
        for (int i = 0; i < numInputs; i++) {
            TransactionInput txIn = uldTx.getInput(i);
            Script inputScript = txIn.getScriptSig();
            List<ScriptChunk> chunks = inputScript.getChunks();
            byte[] program = chunks.get(chunks.size() - 1).data;
            Script redeemScript = new Script(program);
            sighashes.add(uldTx.hashForSignature(i, redeemScript, UldTransaction.SigHash.ALL, false));
        }

        // Verify given signatures are correct before proceeding
        for (int i = 0; i < numInputs; i++) {
            UldECKey.ECDSASignature sig;
            try {
                sig = UldECKey.ECDSASignature.decodeFromDER(signatures.get(i));
            } catch (RuntimeException e) {
                logger.warn("Malformed bpSignature for input {} of tx {}: {}", i, new Keccak256(uscTxHash), Hex.toHexString(signatures.get(i)));
                return;
            }

            Sha256Hash sighash = sighashes.get(i);

            if (!federatorPublicKey.verify(sighash, sig)) {
                logger.warn("Signature {} {} is not valid for hash {} and public key {}", i, Hex.toHexString(sig.encodeToDER()), sighash, federatorPublicKey);
                return;
            }

            TransactionSignature txSig = new TransactionSignature(sig, UldTransaction.SigHash.ALL, false);
            txSigs.add(txSig);
            if (!txSig.isCanonical()) {
                logger.warn("Signature {} {} is not canonical.", i, Hex.toHexString(signatures.get(i)));
                return;
            }
        }

        // All signatures are correct. Proceed to signing
        for (int i = 0; i < numInputs; i++) {
            Sha256Hash sighash = sighashes.get(i);
            TransactionInput input = uldTx.getInput(i);
            Script inputScript = input.getScriptSig();

            boolean alreadySignedByThisFederator = isInputSignedByThisFederator(
                    federatorPublicKey,
                    sighash,
                    input);

            // Sign the input if it wasn't already
            if (!alreadySignedByThisFederator) {
                try {
                    int sigIndex = inputScript.getSigInsertionIndex(sighash, federatorPublicKey);
                    inputScript = ScriptBuilder.updateScriptWithSignature(inputScript, txSigs.get(i).encodeToUlord(), sigIndex, 1, 1);
                    input.setScriptSig(inputScript);
                    logger.debug("Tx input {} for tx {} signed.", i, new Keccak256(uscTxHash));
                } catch (IllegalStateException e) {
                    Federation retiringFederation = getRetiringFederation();
                    if (getActiveFederation().hasPublicKey(federatorPublicKey)) {
                        logger.debug("A member of the active federation is trying to sign a tx of the retiring one");
                        return;
                    } else if (retiringFederation != null && retiringFederation.hasPublicKey(federatorPublicKey)) {
                        logger.debug("A member of the retiring federation is trying to sign a tx of the active one");
                        return;
                    }
                    throw e;
                }
            } else {
                logger.warn("Input {} of tx {} already signed by this federator.", i, new Keccak256(uscTxHash));
                break;
            }
        }

        // If tx fully signed
        if (hasEnoughSignatures(uldTx)) {
            logger.info("Tx fully signed {}. Hex: {}", uldTx, Hex.toHexString(uldTx.ulordSerialize()));
            provider.getUscTxsWaitingForSignatures().remove(new Keccak256(uscTxHash));
            eventLogger.logReleaseUld(uldTx);
        } else {
            logger.debug("Tx not yet fully signed {}.", new Keccak256(uscTxHash));
        }
    }

    /**
     * Check if the p2sh multisig scriptsig of the given input was already signed by federatorPublicKey.
     * @param federatorPublicKey The key that may have been used to sign
     * @param sighash the sighash that corresponds to the input
     * @param input The input
     * @return true if the input was already signed by the specified key, false otherwise.
     */
    private boolean isInputSignedByThisFederator(UldECKey federatorPublicKey, Sha256Hash sighash, TransactionInput input) {
        List<ScriptChunk> chunks = input.getScriptSig().getChunks();
        for (int j = 1; j < chunks.size() - 1; j++) {
            ScriptChunk chunk = chunks.get(j);

            if (chunk.data.length == 0) {
                continue;
            }

            TransactionSignature sig2 = TransactionSignature.decodeFromUlord(chunk.data, false, false);

            if (federatorPublicKey.verify(sighash, sig2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a uld tx has been signed by the required number of federators.
     * @param uldTx The uld tx to check
     * @return True if was signed by the required number of federators, false otherwise
     */
    private boolean hasEnoughSignatures(UldTransaction uldTx) {
        // When the tx is constructed OP_0 are placed where bpSignature should go.
        // Check all OP_0 have been replaced with actual signatures in all inputs
        Context.propagate(uldContext);
        for (TransactionInput input : uldTx.getInputs()) {
            Script scriptSig = input.getScriptSig();
            List<ScriptChunk> chunks = scriptSig.getChunks();
            for (int i = 1; i < chunks.size(); i++) {
                ScriptChunk chunk = chunks.get(i);
                if (!chunk.isOpCode() && chunk.data.length == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the uld tx that federators need to sign or broadcast
     * @return a StateForFederator serialized in RLP
     */
    public byte[] getStateForUldReleaseClient() throws IOException {
        StateForFederator stateForFederator = new StateForFederator(provider.getUscTxsWaitingForSignatures());
        return stateForFederator.getEncoded();
    }

    /**
     * Returns the insternal state of the bridge
     * @return a BridgeState serialized in RLP
     */
    public byte[] getStateForDebugging() throws IOException {
        BridgeState stateForDebugging = new BridgeState(getUldBlockchainBestChainHeight(), provider);

        return stateForDebugging.getEncoded();
    }

    /**
     * Returns the ulord blockchain best chain height know by the bridge contract
     */
    public int getUldBlockchainBestChainHeight() throws IOException {
        return getUldBlockchainChainHead().getHeight();
    }

    /**
     * Returns the Ulord blockchain initial stored block height
     */
    public int getUldBlockchainInitialBlockHeight() throws IOException {
        return getLowestBlock().getHeight();
    }

    /**
     * @deprecated
     * Returns an array of block hashes known by the bridge contract.
     * Federators can use this to find what is the latest block in the mainchain the bridge has.
     * @return a List of ulord block hashes
     */
    @Deprecated
    public List<Sha256Hash> getUldBlockchainBlockLocator() throws IOException {
        StoredBlock  initialUldStoredBlock = this.getLowestBlock();
        final int maxHashesToInform = 100;
        List<Sha256Hash> blockLocator = new ArrayList<>();
        StoredBlock cursor = getUldBlockchainChainHead();
        int bestBlockHeight = cursor.getHeight();
        blockLocator.add(cursor.getHeader().getHash());
        if (bestBlockHeight > initialUldStoredBlock.getHeight()) {
            boolean stop = false;
            int i = 0;
            try {
                while (blockLocator.size() <= maxHashesToInform && !stop) {
                    int blockHeight = (int) (bestBlockHeight - Math.pow(2, i));
                    if (blockHeight <= initialUldStoredBlock.getHeight()) {
                        blockLocator.add(initialUldStoredBlock.getHeader().getHash());
                        stop = true;
                    } else {
                        cursor = this.getPrevBlockAtHeight(cursor, blockHeight);
                        blockLocator.add(cursor.getHeader().getHash());
                    }
                    i++;
                }
            } catch (Exception e) {
                logger.error("Failed to walk the block chain whilst constructing a locator");
                panicProcessor.panic("uldblockchain", "Failed to walk the block chain whilst constructing a locator");
                throw new RuntimeException(e);
            }
            if (!stop) {
                blockLocator.add(initialUldStoredBlock.getHeader().getHash());
            }
        }
        return blockLocator;
    }

    public Sha256Hash getUldBlockchainBlockHashAtDepth(int depth) throws BlockStoreException, IOException {
        Context.propagate(uldContext);
        this.ensureUldBlockChain();

        StoredBlock head = uldBlockChain.getChainHead();

        int maxDepth = head.getHeight() - getLowestBlock().getHeight();

        if (depth < 0 || depth > maxDepth) {
            throw new IndexOutOfBoundsException(String.format("Depth must be between 0 and %d", maxDepth));
        }

        int currentDepth = 0;
        StoredBlock current = head;
        while (currentDepth < depth) {
            current = current.getPrev(uldBlockStore);
            currentDepth++;
        }
        return current.getHeader().getHash();
    }

    private StoredBlock getPrevBlockAtHeight(StoredBlock cursor, int height) throws BlockStoreException {
        if (cursor.getHeight() == height) {
            return cursor;
        }

        boolean stop = false;
        StoredBlock current = cursor;
        while (!stop) {
            current = current.getPrev(this.uldBlockStore);
            stop = current.getHeight() == height;
        }
        return current;
    }

    /**
     * Returns whether a given uld transaction hash has already
     * been processed by the bridge.
     * @param uldTxHash the uld tx hash to check.
     * @return a Boolean indicating whether the given uld tx hash was
     * already processed by the bridge.
     * @throws IOException
     */
    public Boolean isUldTxHashAlreadyProcessed(Sha256Hash uldTxHash) throws IOException {
        return provider.getUldTxHashesAlreadyProcessed().containsKey(uldTxHash);
    }

    /**
     * Returns the USC blockchain height a given uld transaction hash
     * was processed at by the bridge.
     * @param uldTxHash the uld tx hash for which to retrieve the height.
     * @return a Long with the processed height. If the hash was not processed
     * -1 is returned.
     * @throws IOException
     */
    public Long getUldTxHashProcessedHeight(Sha256Hash uldTxHash) throws IOException {
        Map<Sha256Hash, Long> uldTxHashes = provider.getUldTxHashesAlreadyProcessed();

        // Return -1 if the transaction hasn't been processed
        if (!uldTxHashes.containsKey(uldTxHash)) {
            return -1L;
        }

        return uldTxHashes.get(uldTxHash);
    }

    /**
     * Returns the currently active federation.
     * See getActiveFederationReference() for details.
     * @return the currently active federation.
     */
    public Federation getActiveFederation() {
        return federationSupport.getActiveFederation();
    }

    /**
     * Returns the currently retiring federation.
     * See getRetiringFederationReference() for details.
     * @return the retiring federation.
     */
    @Nullable
    public Federation getRetiringFederation() {
        return federationSupport.getRetiringFederation();
    }

    private List<UTXO> getActiveFederationUldUTXOs() throws IOException {
        return federationSupport.getActiveFederationUldUTXOs();
    }

    private List<UTXO> getRetiringFederationUldUTXOs() throws IOException {
        return federationSupport.getRetiringFederationUldUTXOs();
    }

    /**
     * Returns the federation ulord address.
     * @return the federation ulord address.
     */
    public Address getFederationAddress() {
        return getActiveFederation().getAddress();
    }

    /**
     * Returns the federation's size
     * @return the federation size
     */
    public Integer getFederationSize() {
        return federationSupport.getFederationSize();
    }

    /**
     * Returns the federation's minimum required signatures
     * @return the federation minimum required signatures
     */
    public Integer getFederationThreshold() {
        return getActiveFederation().getNumberOfSignaturesRequired();
    }

    /**
     * Returns the public key of the federation's federator at the given index
     * @param index the federator's index (zero-based)
     * @return the federator's public key
     */
    public byte[] getFederatorPublicKey(int index) {
        return federationSupport.getFederatorPublicKey(index);
    }

    /**
     * Returns the federation's creation time
     * @return the federation creation time
     */
    public Instant getFederationCreationTime() {
        return getActiveFederation().getCreationTime();
    }


    /**
     * Returns the federation's creation block number
     * @return the federation creation block number
     */
    public long getFederationCreationBlockNumber() {
        return getActiveFederation().getCreationBlockNumber();
    }

    /**
     * Returns the retiring federation ulord address.
     * @return the retiring federation ulord address, null if no retiring federation exists
     */
    public Address getRetiringFederationAddress() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return null;
        }

        return retiringFederation.getAddress();
    }

    /**
     * Returns the retiring federation's size
     * @return the retiring federation size, -1 if no retiring federation exists
     */
    public Integer getRetiringFederationSize() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return -1;
        }

        return retiringFederation.getPublicKeys().size();
    }

    /**
     * Returns the retiring federation's minimum required signatures
     * @return the retiring federation minimum required signatures, -1 if no retiring federation exists
     */
    public Integer getRetiringFederationThreshold() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return -1;
        }

        return retiringFederation.getNumberOfSignaturesRequired();
    }

    /**
     * Returns the public key of the retiring federation's federator at the given index
     * @param index the retiring federator's index (zero-based)
     * @return the retiring federator's public key, null if no retiring federation exists
     */
    public byte[] getRetiringFederatorPublicKey(int index) {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return null;
        }

        List<UldECKey> publicKeys = retiringFederation.getPublicKeys();

        if (index < 0 || index >= publicKeys.size()) {
            throw new IndexOutOfBoundsException(String.format("Retiring federator index must be between 0 and {}", publicKeys.size() - 1));
        }

        return publicKeys.get(index).getPubKey();
    }

    /**
     * Returns the retiring federation's creation time
     * @return the retiring federation creation time, null if no retiring federation exists
     */
    public Instant getRetiringFederationCreationTime() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return null;
        }

        return retiringFederation.getCreationTime();
    }

    /**
     * Returns the retiring federation's creation block number
     * @return the retiring federation creation block number,
     * -1 if no retiring federation exists
     */
    public long getRetiringFederationCreationBlockNumber() {
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation == null) {
            return -1L;
        }
        return retiringFederation.getCreationBlockNumber();
    }

    /**
     * Returns the currently live federations
     * This would be the active federation plus
     * potentially the retiring federation
     * @return a list of live federations
     */
    private List<Federation> getLiveFederations() {
        List<Federation> liveFederations = new ArrayList<>();
        liveFederations.add(getActiveFederation());
        Federation retiringFederation = getRetiringFederation();
        if (retiringFederation != null) {
            liveFederations.add(retiringFederation);
        }
        return liveFederations;
    }

    /**
     * Creates a new pending federation
     * If there's currently no pending federation and no funds remain
     * to be moved from a previous federation, a new one is created.
     * Otherwise, -1 is returned if there's already a pending federation,
     * -2 is returned if there is a federation awaiting to be active,
     * or -3 if funds are left from a previous one.
     * @param dryRun whether to just do a dry run
     * @return 1 upon success, -1 when a pending federation is present,
     * -2 when a federation is to be activated,
     * and if -3 funds are still to be moved between federations.
     */
    private Integer createFederation(boolean dryRun) throws IOException {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation != null) {
            return -1;
        }

        if (federationSupport.amAwaitingFederationActivation()) {
            return -2;
        }

        if (getRetiringFederation() != null) {
            return -3;
        }

        if (dryRun) {
            return 1;
        }

        currentPendingFederation = new PendingFederation(Collections.emptyList());

        provider.setPendingFederation(currentPendingFederation);

        // Clear votes on election
        provider.getFederationElection(bridgeConstants.getFederationChangeAuthorizer()).clear();

        return 1;
    }

    /**
     * Adds the given key to the current pending federation
     * @param dryRun whether to just do a dry run
     * @param key the public key to add
     * @return 1 upon success, -1 if there was no pending federation, -2 if the key was already in the pending federation
     */
    private Integer addFederatorPublicKey(boolean dryRun, UldECKey key) {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        if (currentPendingFederation.getPublicKeys().contains(key)) {
            return -2;
        }

        if (dryRun) {
            return 1;
        }

        currentPendingFederation = currentPendingFederation.addPublicKey(key);

        provider.setPendingFederation(currentPendingFederation);

        return 1;
    }

    /**
     * Commits the currently pending federation.
     * That is, the retiring federation is set to be the currently active federation,
     * the active federation is replaced with a new federation generated from the pending federation,
     * and the pending federation is wiped out.
     * Also, UTXOs are moved from active to retiring so that the transfer of funds can
     * begin.
     * @param dryRun whether to just do a dry run
     * @param hash the pending federation's hash. This is checked the execution block's pending federation hash for equality.
     * @return 1 upon success, -1 if there was no pending federation, -2 if the pending federation was incomplete,
     * -3 if the given hash doesn't match the current pending federation's hash.
     */
    private Integer commitFederation(boolean dryRun, Keccak256 hash) throws IOException {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        if (!currentPendingFederation.isComplete()) {
            return -2;
        }

        if (!hash.equals(currentPendingFederation.getHash())) {
            return -3;
        }

        if (dryRun) {
            return 1;
        }

        // Move UTXOs from the new federation into the old federation
        // and clear the new federation's UTXOs
        List<UTXO> utxosToMove = new ArrayList<>(provider.getNewFederationUldUTXOs());
        provider.getNewFederationUldUTXOs().clear();
        List<UTXO> oldFederationUTXOs = provider.getOldFederationUldUTXOs();
        oldFederationUTXOs.clear();
        utxosToMove.forEach(utxo -> oldFederationUTXOs.add(utxo));

        // Network parameters for the new federation are taken from the bridge constants.
        // Creation time is the block's timestamp.
        Instant creationTime = Instant.ofEpochMilli(uscExecutionBlock.getTimestamp());
        provider.setOldFederation(getActiveFederation());
        provider.setNewFederation(currentPendingFederation.buildFederation(creationTime, uscExecutionBlock.getNumber(), bridgeConstants.getUldParams()));
        provider.setPendingFederation(null);

        // Clear votes on election
        provider.getFederationElection(bridgeConstants.getFederationChangeAuthorizer()).clear();

        eventLogger.logCommitFederation(uscExecutionBlock, provider.getOldFederation(), provider.getNewFederation());

        return 1;
    }

    /**
     * Rolls back the currently pending federation
     * That is, the pending federation is wiped out.
     * @param dryRun whether to just do a dry run
     * @return 1 upon success, 1 if there was no pending federation
     */
    private Integer rollbackFederation(boolean dryRun) {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        if (dryRun) {
            return 1;
        }

        provider.setPendingFederation(null);

        // Clear votes on election
        provider.getFederationElection(bridgeConstants.getFederationChangeAuthorizer()).clear();

        return 1;
    }

    public Integer voteFederationChange(Transaction tx, ABICallSpec callSpec) {
        // Must be on one of the allowed functions
        if (!FEDERATION_CHANGE_FUNCTIONS.contains(callSpec.getFunction())) {
            return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
        }

        AddressBasedAuthorizer authorizer = bridgeConstants.getFederationChangeAuthorizer();

        // Must be authorized to vote (checking for bpSignature)
        if (!authorizer.isAuthorized(tx)) {
            return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
        }

        // Try to do a dry-run and only register the vote if the
        // call would be successful
        ABICallVoteResult result;
        try {
            result = executeVoteFederationChangeFunction(true, callSpec);
        } catch (IOException e) {
            result = new ABICallVoteResult(false, FEDERATION_CHANGE_GENERIC_ERROR_CODE);
        } catch (BridgeIllegalArgumentException e) {
            result = new ABICallVoteResult(false, FEDERATION_CHANGE_GENERIC_ERROR_CODE);
        }

        // Return if the dry run failed or we are on a reversible execution
        if (!result.wasSuccessful()) {
            return (Integer) result.getResult();
        }

        ABICallElection election = provider.getFederationElection(authorizer);
        // Register the vote. It is expected to succeed, since all previous checks succeeded
        if (!election.vote(callSpec, tx.getSender())) {
            logger.warn("Unexpected federation change vote failure");
            return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
        }

        // If enough votes have been reached, then actually execute the function
        ABICallSpec winnerSpec = election.getWinner();
        if (winnerSpec != null) {
            try {
                result = executeVoteFederationChangeFunction(false, winnerSpec);
            } catch (IOException e) {
                logger.warn("Unexpected federation change vote exception: {}", e.getMessage());
                return FEDERATION_CHANGE_GENERIC_ERROR_CODE;
            } finally {
                // Clear the winner so that we don't repeat ourselves
                election.clearWinners();
            }
        }

        return (Integer) result.getResult();
    }

    private ABICallVoteResult executeVoteFederationChangeFunction(boolean dryRun, ABICallSpec callSpec) throws IOException {
        // Try to do a dry-run and only register the vote if the
        // call would be successful
        ABICallVoteResult result;
        Integer executionResult;
        switch (callSpec.getFunction()) {
            case "create":
                executionResult = createFederation(dryRun);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            case "add":
                byte[] publicKeyBytes = (byte[]) callSpec.getArguments()[0];
                UldECKey publicKey;
                try {
                    publicKey = UldECKey.fromPublicOnly(publicKeyBytes);
                } catch (Exception e) {
                    throw new BridgeIllegalArgumentException("Public key could not be parsed " + Hex.toHexString(publicKeyBytes), e);
                }
                executionResult = addFederatorPublicKey(dryRun, publicKey);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            case "commit":
                Keccak256 hash = new Keccak256((byte[]) callSpec.getArguments()[0]);
                executionResult = commitFederation(dryRun, hash);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            case "rollback":
                executionResult = rollbackFederation(dryRun);
                result = new ABICallVoteResult(executionResult == 1, executionResult);
                break;
            default:
                // Fail by default
                result = new ABICallVoteResult(false, FEDERATION_CHANGE_GENERIC_ERROR_CODE);
        }

        return result;
    }

    /**
     * Returns the currently pending federation hash, or null if none exists
     * @return the currently pending federation hash, or null if none exists
     */
    public byte[] getPendingFederationHash() {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return null;
        }

        return currentPendingFederation.getHash().getBytes();
    }

    /**
     * Returns the currently pending federation size, or -1 if none exists
     * @return the currently pending federation size, or -1 if none exists
     */
    public Integer getPendingFederationSize() {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return -1;
        }

        return currentPendingFederation.getPublicKeys().size();
    }

    /**
     * Returns the currently pending federation threshold, or null if none exists
     * @param index the federator's index (zero-based)
     * @return the pending federation's federator public key
     */
    public byte[] getPendingFederatorPublicKey(int index) {
        PendingFederation currentPendingFederation = provider.getPendingFederation();

        if (currentPendingFederation == null) {
            return null;
        }

        List<UldECKey> publicKeys = currentPendingFederation.getPublicKeys();

        if (index < 0 || index >= publicKeys.size()) {
            throw new IndexOutOfBoundsException(String.format("Federator index must be between 0 and {}", publicKeys.size() - 1));
        }

        return publicKeys.get(index).getPubKey();
    }

    /**
     * Returns the lock whitelist size, that is,
     * the number of whitelisted addresses
     * @return the lock whitelist size
     */
    public Integer getLockWhitelistSize() {
        return provider.getLockWhitelist().getSize();
    }

    /**
     * Returns the lock whitelist address stored
     * at the given index, or null if the
     * index is out of bounds
     * @param index the index at which to get the address
     * @return the base58-encoded address stored at the given index, or null if index is out of bounds
     */
    public LockWhitelistEntry getLockWhitelistEntryByIndex(int index) {
        List<LockWhitelistEntry> entries = provider.getLockWhitelist().getAll();

        if (index < 0 || index >= entries.size()) {
            return null;
        }

        return entries.get(index);
    }

    /**
     *
     * @param addressBase58
     * @return
     */
    public LockWhitelistEntry getLockWhitelistEntryByAddress(String addressBase58) {
        try {
            Address address = getParsedAddress(addressBase58);
            return provider.getLockWhitelist().get(address);
        } catch (AddressFormatException e) {
            logger.warn(INVALID_ADDRESS_FORMAT_MESSAGE, e);
            return null;
        }
    }

    /**
     * Adds the given address to the lock whitelist.
     * Returns 1 upon success, or -1 if the address was
     * already in the whitelist.
     * @param addressBase58 the base58-encoded address to add to the whitelist
     * @param maxTransferValue the max amount of satoshis enabled to transfer for this address
     * @return 1 upon success, -1 if the address was already
     * in the whitelist, -2 if address is invalid
     * LOCK_WHITELIST_GENERIC_ERROR_CODE otherwise.
     */
    public Integer addOneOffLockWhitelistAddress(Transaction tx, String addressBase58, BigInteger maxTransferValue) {
        try {
            Address address = getParsedAddress(addressBase58);
            Coin maxTransferValueCoin = Coin.valueOf(maxTransferValue.longValueExact());
            return this.addLockWhitelistAddress(tx, new OneOffWhiteListEntry(address, maxTransferValueCoin));
        } catch (AddressFormatException e) {
            logger.warn(INVALID_ADDRESS_FORMAT_MESSAGE, e);
            return LOCK_WHITELIST_INVALID_ADDRESS_FORMAT_ERROR_CODE;
        }
    }

    public Integer addUnlimitedLockWhitelistAddress(Transaction tx, String addressBase58) {
        try {
            Address address = getParsedAddress(addressBase58);
            return this.addLockWhitelistAddress(tx, new UnlimitedWhiteListEntry(address));
        } catch (AddressFormatException e) {
            logger.warn(INVALID_ADDRESS_FORMAT_MESSAGE, e);
            return LOCK_WHITELIST_INVALID_ADDRESS_FORMAT_ERROR_CODE;
        }
    }

    private Integer addLockWhitelistAddress(Transaction tx, LockWhitelistEntry entry) {
        if (!isLockWhitelistChangeAuthorized(tx)) {
            return LOCK_WHITELIST_GENERIC_ERROR_CODE;
        }

        LockWhitelist whitelist = provider.getLockWhitelist();

        try {
            if (whitelist.isWhitelisted(entry.address())) {
                return LOCK_WHITELIST_ALREADY_EXISTS_ERROR_CODE;
            }
            whitelist.put(entry.address(), entry);
            return LOCK_WHITELIST_SUCCESS_CODE;
        } catch (Exception e) {
            logger.error("Unexpected error in addLockWhitelistAddress: {}", e);
            panicProcessor.panic("lock-whitelist", e.getMessage());
            return LOCK_WHITELIST_UNKNOWN_ERROR_CODE;
        }
    }

    private boolean isLockWhitelistChangeAuthorized(Transaction tx) {
        AddressBasedAuthorizer authorizer = bridgeConstants.getLockWhitelistChangeAuthorizer();

        return authorizer.isAuthorized(tx);
    }

    /**
     * Removes the given address from the lock whitelist.
     * Returns 1 upon success, or -1 if the address was
     * not in the whitelist.
     * @param addressBase58 the base58-encoded address to remove from the whitelist
     * @return 1 upon success, -1 if the address was not
     * in the whitelist, -2 if the address is invalid,
     * LOCK_WHITELIST_GENERIC_ERROR_CODE otherwise.
     */
    public Integer removeLockWhitelistAddress(Transaction tx, String addressBase58) {
        if (!isLockWhitelistChangeAuthorized(tx)) {
            return LOCK_WHITELIST_GENERIC_ERROR_CODE;
        }

        LockWhitelist whitelist = provider.getLockWhitelist();

        try {
            Address address = getParsedAddress(addressBase58);

            if (!whitelist.remove(address)) {
                return -1;
            }

            return 1;
        } catch (AddressFormatException e) {
            return -2;
        } catch (Exception e) {
            logger.error("Unexpected error in removeLockWhitelistAddress: {}", e.getMessage());
            panicProcessor.panic("lock-whitelist", e.getMessage());
            return 0;
        }
    }

    /**
     * Returns the minimum amount of satoshis a user should send to the federation.
     * @return the minimum amount of satoshis a user should send to the federation.
     */
    public Coin getMinimumLockTxValue() {
        return bridgeConstants.getMinimumLockTxValue();
    }

    /**
     * Votes for a fee per kb value.
     *
     * @return 1 upon successful vote, -1 when the vote was unsuccessful,
     * FEE_PER_KB_GENERIC_ERROR_CODE when there was an un expected error.
     */
    public Integer voteFeePerKbChange(Transaction tx, Coin feePerKb) {
        AddressBasedAuthorizer authorizer = bridgeConstants.getFeePerKbChangeAuthorizer();
        if (!authorizer.isAuthorized(tx)) {
            return FEE_PER_KB_GENERIC_ERROR_CODE;
        }

        ABICallElection feePerKbElection = provider.getFeePerKbElection(authorizer);
        ABICallSpec feeVote = new ABICallSpec("setFeePerKb", new byte[][]{BridgeSerializationUtils.serializeCoin(feePerKb)});
        boolean successfulVote = feePerKbElection.vote(feeVote, tx.getSender());
        if (!successfulVote) {
            return -1;
        }

        ABICallSpec winner = feePerKbElection.getWinner();
        if (winner == null) {
            logger.info("Successful fee per kb vote for {}", feePerKb);
            return 1;
        }

        Coin winnerFee;
        try {
            winnerFee = BridgeSerializationUtils.deserializeCoin(winner.getArguments()[0]);
        } catch (Exception e) {
            logger.warn("Exception deserializing winner feePerKb", e);
            return FEE_PER_KB_GENERIC_ERROR_CODE;
        }

        if (winnerFee == null) {
            logger.warn("Invalid winner feePerKb: feePerKb can't be null");
            return FEE_PER_KB_GENERIC_ERROR_CODE;
        }

        if (!winnerFee.equals(feePerKb)) {
            logger.debug("Winner fee is different than the last vote: maybe you forgot to clear winners");
        }

        logger.info("Fee per kb changed to {}", winnerFee);
        provider.setFeePerKb(winnerFee);
        feePerKbElection.clear();
        return 1;
    }

    /**
     * Sets a delay in the USC best chain to disable lock whitelist
     * @param tx current USC transaction
     * @param disableBlockDelayBI USC block height to disable lock whitelist
     * @return 1 if it was successful, -1 if a delay was already set, -2 if disableBlockDelay contains an invalid value
     */
    public Integer setLockWhitelistDisableBlockDelay(Transaction tx, BigInteger disableBlockDelayBI) throws IOException {
        if (!isLockWhitelistChangeAuthorized(tx)) {
            return LOCK_WHITELIST_GENERIC_ERROR_CODE;
        }

        long disableBlockDelay = disableBlockDelayBI.longValueExact();
        long bestChainHeight = blockStore.getBestBlock().getNumber();
        if (disableBlockDelay < 0 || Long.MAX_VALUE - disableBlockDelay < bestChainHeight) {
            return -2;
        }

        LockWhitelist lockWhitelist = provider.getLockWhitelist();
        lockWhitelist.setDisableBlockHeight(bestChainHeight + disableBlockDelay);

        return 1;
    }

    private StoredBlock getUldBlockchainChainHead() throws IOException {
        // Gather the current uld chain's head
        // IMPORTANT: we assume that getting the chain head from the uld blockstore
        // is enough since we're not manipulating the blockchain here, just querying it.
        try {
            this.ensureUldBlockStore();
            return uldBlockStore.getChainHead();
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
    }

    /**
     * Returns the first ulord block we have. It is either a checkpoint or the genesis
     */
    private StoredBlock getLowestBlock() throws IOException {
        InputStream checkpoints = this.getCheckPoints();
        if (checkpoints == null) {
            UldBlock genesis = bridgeConstants.getUldParams().getGenesisBlock();
            return new StoredBlock(genesis, genesis.getWork(), 0);
        }
        CheckpointManager manager = new CheckpointManager(bridgeConstants.getUldParams(), checkpoints);
        long time = getActiveFederation().getCreationTime().toEpochMilli();
        // Go back 1 week to match CheckpointManager.checkpoint() behaviour
        time -= 86400 * 7;
        return manager.getCheckpointBefore(time);
    }

    private Pair<UldTransaction, List<UTXO>> createMigrationTransaction(Wallet originWallet, Address destinationAddress) {
        Coin expectedMigrationValue = originWallet.getBalance();
        for(;;) {
            UldTransaction migrationUldTx = new UldTransaction(originWallet.getParams());
            migrationUldTx.addOutput(expectedMigrationValue, destinationAddress);

            SendRequest sr = SendRequest.forTx(migrationUldTx);
            sr.changeAddress = destinationAddress;
            sr.feePerKb = getFeePerKb();
            sr.missingSigsMode = Wallet.MissingSigsMode.USE_OP_ZERO;
            sr.recipientsPayFees = true;
            try {
                originWallet.completeTx(sr);
                for (TransactionInput transactionInput : migrationUldTx.getInputs()) {
                    transactionInput.disconnect();
                }

                List<UTXO> selectedUTXOs = originWallet
                        .getUTXOProvider().getOpenTransactionOutputs(originWallet.getWatchedAddresses()).stream()
                        .filter(utxo ->
                                migrationUldTx.getInputs().stream().anyMatch(input ->
                                        input.getOutpoint().getHash().equals(utxo.getHash()) &&
                                                input.getOutpoint().getIndex() == utxo.getIndex()
                                )
                        )
                        .collect(Collectors.toList());

                return Pair.of(migrationUldTx, selectedUTXOs);
            } catch (InsufficientMoneyException | Wallet.ExceededMaxTransactionSize | Wallet.CouldNotAdjustDownwards e) {
                expectedMigrationValue = expectedMigrationValue.divide(2);
            } catch(Wallet.DustySendRequested e) {
                throw new IllegalStateException("Retiring federation wallet cannot be emptied", e);
            } catch (UTXOProviderException e) {
                throw new RuntimeException("Unexpected UTXO provider error", e);
            }
        }
    }

    // Make sure the local ulord blockchain is instantiated
    private void ensureUldBlockChain() throws IOException {
        this.ensureUldBlockStore();

        if (this.uldBlockChain == null) {
            try {
                this.uldBlockChain = new UldBlockChain(uldContext, uldBlockStore);
            } catch (BlockStoreException e) {
                throw new IOException(e);
            }
        }
    }

    // Make sure the local ulord blockstore is instantiated
    private void ensureUldBlockStore() throws IOException {
        if (this.uldBlockStore == null) {
            try {
                this.uldBlockStore = this.buildRepositoryBlockStore();
            } catch (BlockStoreException e) {
                throw new IOException(e);
            }
        }
    }

    private Address getParsedAddress(String base58Address) throws AddressFormatException {
        return Address.fromBase58(uldContext.getParams(), base58Address);
    }
}

