/* SPDX-License-Identifier: Apache-2.0 */

package tech.pegasys.samples.crosschain.simple.transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.besu.Besu;
import org.web3j.protocol.besu.response.crosschain.CrossIsLockedResponse;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.CrosschainContext;
import org.web3j.tx.CrosschainContextGenerator;
import org.web3j.tx.CrosschainTransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.gas.StaticGasProvider;
import tech.pegasys.samples.crosschain.simple.soliditywrappers.Sc1Contract1;
import tech.pegasys.samples.crosschain.simple.soliditywrappers.Sc2Contract2;
import tech.pegasys.samples.sidechains.common.coordination.CrosschainCoordinationContractSetup;
import tech.pegasys.samples.sidechains.common.utils.BasePropertiesFile;
import tech.pegasys.samples.sidechains.common.utils.KeyPairGen;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigInteger;
import java.util.Scanner;

import static tech.pegasys.samples.sidechains.common.coordination.CrosschainCoordinationContractSetup.*;

/* This example runs a Crosschain Transaction from Contract1 in Chain 1 to Contract2 in chain 2.
 * Steps:
 * - App deploys Contract1 in chain 1 and Contract2 in chain 2.
 * - App calls directly Contract2.set() to set variable `val` to false.
 * - App changes that same variable through a crosschain call, by calling Contract1.crosschain_setter()
 *      - Contract1.crosschain_setter() calls Contract2.set() in Chain 2, which sets `val` to true
 * - App calls directly Contract2.get() to show that the initial value has been changed by the crosschain transaction.
 */

public class CrosschainTransactionNoParams {
    private static final Logger LOG = LogManager.getLogger(CrosschainTransactionNoParams.class);

    private static final BigInteger SC1_SIDECHAIN_ID = BigInteger.valueOf(22);
    private static final String SC1_IP_PORT = "127.0.0.1:8220";
    private static final String SC1_URI = "http://" + SC1_IP_PORT + "/";
    private static final BigInteger SC2_SIDECHAIN_ID = BigInteger.valueOf(33);
    private static final String SC2_IP_PORT = "127.0.0.1:8330";
    private static final String SC2_URI = "http://" + SC2_IP_PORT + "/";

    // Have the polling interval equal to the block time.
    private static final int POLLING_INTERVAL = 2000;
    // Retry reqests to Ethereum Clients up to five times.
    private static final int RETRY = 5;

    // Time-out for Crosschain Transactions in terms of block numbers on SC0.
    private static final int CROSSCHAIN_TRANSACTION_TIMEOUT = 10;

    // Properties in the file.
    private static final String PRIVATE_KEY = "PrivateKey";
    private static final String CONTRACT1_ADDRESS = "Contract1Address";
    private static final String CONTRACT2_ADDRESS = "Contract2Address";

    // Externally Owned Account key pair.
    private Credentials credentials;

    // Web services for each blockchain / sidechain.
    private Besu web3jSc1;
    private Besu web3jSc2;

    // A gas provider which indicates no gas is charged for transactions.
    private ContractGasProvider freeGasProvider;

    // Transaction manager for each sidechain.
    private CrosschainTransactionManager tmSc1;
    private CrosschainTransactionManager tmSc2;

    // Smart contract addresses and objected.of contracts.
    private String contract1Address = null;
    private String contract2Address = null;

    private Sc1Contract1 contract1;
    private Sc2Contract2 contract2;

    private CrosschainCoordinationContractSetup coordinationContractSetup;


    private static boolean automatedRun = false;

    public static void main(final String args[]) throws Exception {
        LOG.info("Crosschain Transaction - No Parameters - started");
        new CrosschainTransactionNoParams().run();
    }

    public static void automatedRun() throws Exception {
        // Delete all properties files as a starting point. This will ensure all contracts are redeployed.
        deleteAllPropertiesFile();

        // Run the samples in a way that does not require input from the keyboard.
        automatedRun = true;
        new CrosschainTransactionNoParams().run();

        // Clean-up.
        deleteAllPropertiesFile();
    }

    private static void deleteAllPropertiesFile() throws IOException {
        new SampleProperties().deletePropertiesFile();
    }



    private void run() throws Exception {
        loadStoreProperties();
        LOG.info("Using credentials which correspond to account: {}", this.credentials.getAddress());

        core();
    }

    private void setupBesuServiceTransactionManager() throws Exception {
        LOG.info("Setting up Besu service and transaction managers");

        this.web3jSc1 = Besu.build(new HttpService(SC1_URI), POLLING_INTERVAL);
        this.web3jSc2 = Besu.build(new HttpService(SC2_URI), POLLING_INTERVAL);

        // Note that the multi-chain node is assumed to be configured.
        // If this is not the case, please use the Multichain Manager sample with the options "config auto".
        CrosschainCoordinationContractSetup coordinationContractSetup = new CrosschainCoordinationContractSetup(this.web3jSc1);

        this.tmSc1 = new CrosschainTransactionManager(this.web3jSc1, this.credentials, SC1_SIDECHAIN_ID, RETRY, POLLING_INTERVAL,
            coordinationContractSetup.getCrosschainCoordinationWeb3J(),
            coordinationContractSetup.getCrosschainCoordinationContractBlockcainId(),
            coordinationContractSetup.getCrosschainCoordinationContractAddress(),
            CROSSCHAIN_TRANSACTION_TIMEOUT);
        this.tmSc2 = new CrosschainTransactionManager(this.web3jSc2, this.credentials, SC2_SIDECHAIN_ID, RETRY, POLLING_INTERVAL,
            coordinationContractSetup.getCrosschainCoordinationWeb3J(),
            coordinationContractSetup.getCrosschainCoordinationContractBlockcainId(),
            coordinationContractSetup.getCrosschainCoordinationContractAddress(),
            CROSSCHAIN_TRANSACTION_TIMEOUT);

        // Hyperledger Besu is configured as an IBFT2, free gas network. We need a free gas provider.
        this.freeGasProvider = new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);
    }

    private void loadContracts() {
        LOG.info("Loading contracts");
        LOG.info(" Contract 1: {}", this.contract1Address);
        LOG.info(" Contract 2: {}", this.contract2Address);

        this.contract1 = Sc1Contract1.load(this.contract1Address, this.web3jSc1, this.tmSc1, this.freeGasProvider);
        this.contract2 = Sc2Contract2.load(this.contract2Address, this.web3jSc2, this.tmSc2, this.freeGasProvider);
    }

    private void deployContracts() throws Exception {
        LOG.info("Deploying contracts");
        RemoteCall<Sc2Contract2> remoteCallContract2 =
                Sc2Contract2.deployLockable(this.web3jSc2, this.tmSc2, this.freeGasProvider);
        this.contract2 = remoteCallContract2.send();
        this.contract2Address = this.contract2.getContractAddress();
        LOG.info(" Contract 2 deployed on sidechain 2 (id={}), at address: {}",  SC2_SIDECHAIN_ID, contract2Address);

        RemoteCall<Sc1Contract1> remoteCallContract1 =
                Sc1Contract1.deployLockable(this.web3jSc1, this.tmSc1, this.freeGasProvider, SC2_SIDECHAIN_ID, contract2Address);
        this.contract1 = remoteCallContract1.send();
        this.contract1Address = this.contract1.getContractAddress();
        LOG.info(" Contract 1 deployed on sidechain 1 (id={}), at address: {}", SC1_SIDECHAIN_ID, contract1Address);
    }




    private void core() throws Exception {
        LOG.info("Running Core Part of Sample Code");

        LOG.info(" Set state in each contract to known values that aren't zero.");
        TransactionReceipt transactionReceipt;
        LOG.info("  Single-chain transaction: Contract2.clear()");
        transactionReceipt = this.contract2.clear().send();
        assertTrue(transactionReceipt.isStatusOK());

        checkExpectedValues(false);

        Scanner myInput = new Scanner( System.in );
        Boolean c2Val = this.contract2.get().send();

        LOG.info("  Executing call simulator to determine parameter values and expected results");
        CallSimulator sim = new CallSimulator();
        sim.c2clear();
        sim.c1Crosschain_setter();

        LOG.info("  Constructing Nested Crosschain Transaction");
        // Originating sidechain is sidechain 1.
        CrosschainContextGenerator contextGenerator = new CrosschainContextGenerator(SC1_SIDECHAIN_ID);

        // Call to contract 2
        // Contract 2 is called by contract 1 on sidechain 1.
        CrosschainContext subordinateContext = contextGenerator.createCrosschainContext(SC1_SIDECHAIN_ID, this.contract1Address);
        byte[] subordinateTransactionC2 = this.contract2.set_AsSignedCrosschainSubordinateTransaction(subordinateContext);

        // Call to contract 1
        byte[][] subordinateTransactionsAndViewsForC1;
        subordinateTransactionsAndViewsForC1 = new byte[][] {subordinateTransactionC2};

        LOG.info("  Executing Crosschain Transaction");
        // The originating transaction starts in Contract 1.
        subordinateContext = contextGenerator.createCrosschainContext(subordinateTransactionsAndViewsForC1);
        transactionReceipt = this.contract1.crosschain_setter_AsCrosschainOriginatingTransaction(subordinateContext).send();
        LOG.info("  Transaction Receipt: {}", transactionReceipt.toString());
        assertTrue(transactionReceipt.isStatusOK());

        boolean stillLocked;
        final int tooLong = 10;
        int longTimeCount = 0;
        StringBuffer graphicalCount = new StringBuffer();
        do {
            longTimeCount++;
            if (longTimeCount > tooLong) {
                LOG.error("Contract {} did not unlock", this.contract1Address);
            }
            Thread.sleep(100);
            CrossIsLockedResponse isLockedObj = this.web3jSc1.crossIsLocked(this.contract1Address, DefaultBlockParameter.valueOf("latest")).send();
            stillLocked = isLockedObj.isLocked();
            if (stillLocked) {
                graphicalCount.append(".");
                LOG.info("   Waiting for the contract to unlock{}", graphicalCount.toString());
            }
        } while (stillLocked);
        Thread.sleep(3000);

        checkExpectedValues(sim.c2Get());
    }


    private void checkExpectedValues(Boolean v1) throws Exception {
        LOG.info(" Check values have been set as expected");
        Boolean result;

        result = this.contract2.get().send();
        LOG.info("  Contract2.val = {}, expecting {}", result.booleanValue(), v1);
        assertTrue(result.equals(v1));
    }


    private static void assertTrue(boolean val) {
        if (!val) {
            throw new Error("Unexpected Result");
        }
    }




    private void loadStoreProperties() throws Exception {
        SampleProperties props = new SampleProperties();
        if (props.propertiesFileExists()) {
            LOG.info("Properties file exists, loading");
            props.loadProperties();
        }
        else {
            this.credentials = Credentials.create(new KeyPairGen().generateKeyPairGetPrivateKey());
            setupBesuServiceTransactionManager();
            deployContracts();
            // Generate a key and store it in the format required for Credentials.
            props.properties.setProperty(PRIVATE_KEY, new KeyPairGen().generateKeyPairGetPrivateKey());
            props.properties.setProperty(CONTRACT1_ADDRESS, this.contract1Address);
            props.properties.setProperty(CONTRACT2_ADDRESS, this.contract2Address);
            props.storeProperties();
        }
        this.credentials = Credentials.create(props.properties.getProperty(PRIVATE_KEY));
        this.contract1Address = props.properties.getProperty(CONTRACT1_ADDRESS);
        this.contract2Address = props.properties.getProperty(CONTRACT2_ADDRESS);
        setupBesuServiceTransactionManager();
        loadContracts();

    }


    static class SampleProperties extends BasePropertiesFile {

        SampleProperties() {
            super(MethodHandles.lookup().lookupClass().getEnclosingClass().getSimpleName());
        }

    }
}
