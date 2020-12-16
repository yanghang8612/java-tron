package stest.tron.wallet.benchmark;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.tron.api.DatabaseGrpc;
import org.tron.api.GrpcAPI;
import org.tron.api.WalletGrpc;
import org.tron.api.WalletSolidityGrpc;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.FileUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.interfaceOnSolidity.RpcApiServiceOnSolidity;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.TransactionInfo;
import stest.tron.wallet.common.client.Configuration;
import stest.tron.wallet.common.client.utils.Base58;
import stest.tron.wallet.common.client.utils.PublicMethed;

import java.io.File;
import java.math.BigInteger;
import java.util.List;

@Slf4j
public class JustlinkBenchMark {

  private TronApplicationContext context;
  private ManagedChannel channelFull = null;
  private WalletGrpc.WalletBlockingStub blockingStubFull = null;
  private WalletSolidityGrpc.WalletSolidityBlockingStub blockingStubSolidity = null;
  private DatabaseGrpc.DatabaseBlockingStub databaseBlockingStub = null;
  private RpcApiService rpcApiService;
  private RpcApiServiceOnSolidity rpcApiServiceOnSolidity;
  private Application appTest;
  private Manager manager;
  private String databaseDir;
  protected String dbPath;

  // account with lots of balance
  private String testFoundationKey = "D95611A9AF2A2A45359106222ED1AFED48853D9A44DEFF8DC7913F5CBA727366";
  private byte[] testFoundationAddress = getFinalAddress(testFoundationKey);
  private String testFoundationAddressString = PublicMethed.getAddressString(testFoundationKey);

  private Long maxFeeLimit = Configuration.getByPath("testng.conf")
      .getLong("defaultParameter.maxFeeLimit");
  private String fullnode;
  private byte[] JustMidcontractAddress;
  private byte[] AggregatorcontractAddress;
  private byte[] SunJstcontractAddress;
  private List<byte[]> OraclecontractAddress = new ArrayList<>(7);

  // config testng.conf defaultParameter.solidityCompile as local solc first

  @BeforeClass
  public void before() {
    dbPath = "output_" + this.getClass().getName();
    Args.setParam(new String[]{"-d", dbPath, "-w"}, "config-localtest.conf");
    // allow account root
    Args.getInstance().setAllowAccountStateRoot(1);
    databaseDir = Args.getInstance().getStorage().getDbDirectory();

    Args.getInstance().getStorage().setDbEngine("LEVELDB");
    // start fullnode
    startApp();
  }

  @AfterClass(enabled = true)
  public void destroy() {
    // stop the node
    shutdown();

    delete(dbPath);
  }

  /**
   * init logic.
   */
  public void startApp() {
    context = new TronApplicationContext(DefaultConfig.class);
    appTest = ApplicationFactory.create(context);
    rpcApiService = context.getBean(RpcApiService.class);
    rpcApiServiceOnSolidity = context.getBean(RpcApiServiceOnSolidity.class);
    appTest.addService(rpcApiService);
    appTest.addService(rpcApiServiceOnSolidity);
    appTest.initServices(Args.getInstance());
    appTest.startServices();
    appTest.startup();

    fullnode = String.format("%s:%d", "127.0.0.1",
        Args.getInstance().getRpcPort());
    channelFull = ManagedChannelBuilder.forTarget(fullnode)
        .usePlaintext(true)
        .build();
    blockingStubFull = WalletGrpc.newBlockingStub(channelFull);
    blockingStubSolidity = WalletSolidityGrpc.newBlockingStub(channelFull);
    databaseBlockingStub = DatabaseGrpc.newBlockingStub(channelFull);

    manager = appTest.getDbManager();
  }

  /**
   * Delete the database when exit.
   */
  public void delete(String dbPath) {
    if (FileUtil.deleteDir(new File(dbPath))) {
      logger.info("Release resources successful.");
    } else {
      logger.info("Release resources failure.");
    }
  }

  /**
   * shutdown the fullnode.
   */
  public void shutdown() {
    appTest.shutdownServices();
    appTest.shutdown();
    context.destroy();
  }

  public byte[] getFinalAddress(String priKey) {
    Wallet.setAddressPreFixByte((byte) 0x41);
    ECKey key = ECKey.fromPrivate(new BigInteger(priKey, 16));
    return key.getAddress();
  }

  public static GrpcAPI.Return broadcastTransaction(
      Protocol.Transaction transaction, WalletGrpc.WalletBlockingStub blockingStubFull) {
    int i = 10;
    GrpcAPI.Return response = blockingStubFull.broadcastTransaction(transaction);
    while (!response.getResult() && response.getCode() == GrpcAPI.Return.response_code.SERVER_BUSY
        && i > 0) {
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      i--;
      response = blockingStubFull.broadcastTransaction(transaction);
    }
    return response;
  }

  public void processTransaction(Protocol.Transaction transaction) throws Exception {
    manager.processTransaction(new TransactionCapsule(transaction), null);
  }

  @Test
  public void test001() {
    String contractName = "TronToken";
    String parame = "\"" + testFoundationAddressString + "\"";
    logger.info("parame : " + parame);
    String filePath = "./src/test/resources/soliditycode/contractScenario004.sol";
    HashMap<String, String> retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode");
    String abi = retMap.get("abI");
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName,
            abi, code, "constructor(address)"
            , parame
            , "",
            maxFeeLimit, 0L, 100,
            null, testFoundationKey,
            testFoundationAddress, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo extn = PublicMethed
        .getTransactionInfoById(Txid, blockingStubFull).get();
    SunJstcontractAddress = extn.getContractAddress().toByteArray();
    logger.info("contractAddress: " + Base58.encode58Check(SunJstcontractAddress));
  }

  @Test
  public void test002() {
    String contractName = "JustMid";
    String parame = "\"" + Base58.encode58Check(SunJstcontractAddress) + "\"";
    logger.info("parame : " + parame);
    String filePath = "./src/test/resources/soliditycode/JustMid.sol";
    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode").toString();
    String abi = retMap.get("abI").toString();
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName, abi, code, "constructor(address)", parame,
            "", maxFeeLimit, 0L, 100,
            null, testFoundationKey,
            testFoundationAddress, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo extn = PublicMethed
        .getTransactionInfoById(Txid, blockingStubFull).get();
    JustMidcontractAddress = extn.getContractAddress().toByteArray();
    logger.info("contractAddress: " + Base58.encode58Check(JustMidcontractAddress));
  }

  @Test
  public void test003() {
    String contractName = "Aggregator";
    String parame =
        "\"" + Base58.encode58Check(SunJstcontractAddress) + "\",\""
            + Base58.encode58Check(JustMidcontractAddress) + "\"";
    logger.info("parame : " + parame);
    String filePath = "./src/test/resources/soliditycode/TronUser.sol";
    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode").toString();
    String abi = retMap.get("abI").toString();
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName, abi, code, "constructor(address,address)"
            , parame, "", maxFeeLimit, 0L, 100,
            null, testFoundationKey,
            testFoundationAddress, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo extn = PublicMethed
        .getTransactionInfoById(Txid, blockingStubFull).get();
    AggregatorcontractAddress = extn.getContractAddress().toByteArray();
    logger.info("contractAddress: " + Base58.encode58Check(AggregatorcontractAddress));
  }

  @Test(invocationCount = 7)
  public void test004() {
    String contractName = "Oracle";
    String parame =
        "\"" + Base58.encode58Check(SunJstcontractAddress) + "\",\""
            + Base58.encode58Check(JustMidcontractAddress) + "\"";
    logger.info("parame : " + parame);
    String filePath = "./src/test/resources/soliditycode/TronOracles.sol";
    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode").toString();
    String abi = retMap.get("abI").toString();
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName, abi, code, "constructor(address,address)"
            , parame, "", maxFeeLimit, 0L, 100,
            null, testFoundationKey,
            testFoundationAddress, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo extn = PublicMethed
        .getTransactionInfoById(Txid, blockingStubFull).get();
    byte[] singleOracleContractAddress = extn.getContractAddress().toByteArray();
    OraclecontractAddress.add(singleOracleContractAddress);
    logger.info("contractAddress: " + Base58.encode58Check(singleOracleContractAddress));
  }

  @Test(enabled = true, priority = 99)
  public void bn256addTest001() {
    String methodStr = "updateRequestDetails(uint128,uint128,address[],bytes32[])";
    String data = String.format("\"123\",\"7\",[\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"],"
            + "[\"bb347a9a63324fd995a7159cb0c8348a\",\"40691f5fd4b64ab4a5442477ed484d80\",\"f7ccb652cc254a19b0b954c49af25926\",\"38cd68072a6c4a0ca05e9b91976cf4f1\",\"328697ef599043e1a301ae985d06aabf\",\"239ff4228974435ea33f7c32cb46d297\",\"10ee483bad154f41ac58fdb4010c2c63\"]",
        Base58.encode58Check(OraclecontractAddress.get(0)),
        Base58.encode58Check(OraclecontractAddress.get(1)),
        Base58.encode58Check(OraclecontractAddress.get(2)),
        Base58.encode58Check(OraclecontractAddress.get(3)),
        Base58.encode58Check(OraclecontractAddress.get(4)),
        Base58.encode58Check(OraclecontractAddress.get(5)),
        Base58.encode58Check(OraclecontractAddress.get(6)));
    logger.info("data: " + data);
    String txid = PublicMethed
        .triggerContract(AggregatorcontractAddress, methodStr, data, false, 0, maxFeeLimit,
            testFoundationAddress, testFoundationKey, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo option = PublicMethed
        .getTransactionInfoById(txid, blockingStubFull).get();
    long energyCost = option.getReceipt().getEnergyUsageTotal();
    logger.info("energyCost: " + energyCost);
  }

  @Test(enabled = true, invocationCount = 10, priority = 100)
  public void justlinkBenchMark() {
    String methodStr = "requestRateUpdate()";
    String data = "#";
    String txid = PublicMethed
        .triggerContract(AggregatorcontractAddress, methodStr, data, false, 0, maxFeeLimit,
            testFoundationAddress, testFoundationKey, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo option = PublicMethed
        .getTransactionInfoById(txid, blockingStubFull).get();
    long energyCost = option.getReceipt().getEnergyUsageTotal();
    logger.info("energyCost: " + energyCost);
  }
}
