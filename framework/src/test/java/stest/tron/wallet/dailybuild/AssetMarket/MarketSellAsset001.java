package stest.tron.wallet.dailybuild.AssetMarket;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;
import org.tron.api.WalletGrpc;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Utils;
import org.tron.core.Wallet;
import org.tron.protos.Protocol.TransactionInfo;
import stest.tron.wallet.common.client.Configuration;
import stest.tron.wallet.common.client.Parameter;
import stest.tron.wallet.common.client.utils.Base58;
import stest.tron.wallet.common.client.utils.PublicMethed;
@Slf4j
public class MarketSellAsset001 {
  private String testFoundationKey ="2925e186bb1e88988855f11ebf20ea3a6e19ed9232821312b576122e769d45b68";
  private byte[] testFoundationAddress = PublicMethed.getFinalAddress(testFoundationKey);
  private Long maxFeeLimit = Configuration.getByPath("testng.conf")
      .getLong("defaultParameter.maxFeeLimit");
  private String fullnode = "47.94.224.107:50051";
  private ManagedChannel channelFull = null;
  private WalletGrpc.WalletBlockingStub blockingStubFull = null;
  ECKey ecKey1 = new ECKey(Utils.getRandom());
  byte[] testAddress001 = ecKey1.getAddress();
  String testKey001 = ByteArray.toHexString(ecKey1.getPrivKeyBytes());
  private byte[] contractAddress;
  private byte[] JustMidcontractAddress;
  private byte[] AggregatorcontractAddress;
  private byte[] SunJstcontractAddress;
  private byte[] OraclecontractAddress;
  private int invocationCount = 0;
  @BeforeSuite
  public void beforeSuite() {
    Wallet wallet = new Wallet();
    Wallet.setAddressPreFixByte(Parameter.CommonConstant.ADD_PRE_FIX_BYTE_MAINNET);
  }
  /**
   * constructor.
   */
  @BeforeClass(enabled = true)
  public void beforeClass() {
    PublicMethed.printAddress(testKey001);
    channelFull = ManagedChannelBuilder.forTarget(fullnode).usePlaintext(true).build();
    blockingStubFull = WalletGrpc.newBlockingStub(channelFull);
//    PublicMethed
//        .sendcoin(testAddress001, 1000_000_000L, testFoundationAddress, testFoundationKey,
//        blockingStubFull);
//    PublicMethed.waitProduceNextBlock(blockingStubFull);
//    String filePath = "src/test/resources/soliditycode/create2Istanbul.sol";
//    String contractName = "create2Istanbul";
//    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
//    String code = retMap.get("byteCode").toString();
//    String abi = retMap.get("abI").toString();
//    contractAddress = PublicMethed
//      .deployContract(contractName, abi, code, "", maxFeeLimit, 0L, 100, null, testKey001,
//        testAddress001, blockingStubFull);
  }
  /**
   * Create2 Algorithm Changed
   * Before: according to msg.sender`s Address, salt, bytecode to get create2 Address
   * After : according to contract`s Address, salt, bytecode to get create2 Address
   * The calculated Create2 address should be same as get(bytes1,bytes,uint256)
   */
  // SUNJST  : TEEQrt9PjTAzfmTA7fnPA8GRdPG2Czn1SK
  // JustMid : TLQbxFUDkaXEypkFQjrL1exD6sCMr2h2zj
  //
  //
  @Test
  public void test001(){
    String contractName = "TronToken";
    String parame = "\"" + PublicMethed.getAddressString(testFoundationKey) + "\"";
    logger.info("parame : "  + parame);
    String filePath = "./src/test/resources/soliditycode/contractScenario004.sol";
    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode").toString();
    String abi = retMap.get("abI").toString();
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName,
            abi, code, "constructor(address)"
            ,parame
            ,"",
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
  public void test002(){
    String contractName = "JustMid";
    String parame = "\"" + Base58.encode58Check(SunJstcontractAddress) + "\"";
    logger.info("parame : "  + parame);
    String filePath = "./src/test/resources/soliditycode/JustMid.sol";
    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode").toString();
    String abi = retMap.get("abI").toString();
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName, abi, code, "constructor(address)",parame,"", maxFeeLimit, 0L, 100,
            null, testFoundationKey,
            testFoundationAddress, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo extn = PublicMethed
        .getTransactionInfoById(Txid, blockingStubFull).get();
    JustMidcontractAddress = extn.getContractAddress().toByteArray();
    logger.info("contractAddress: " + Base58.encode58Check(JustMidcontractAddress));
  }
  @Test
  public void test003(){
    String contractName = "Aggregator";
    String parame =
        "\"" + Base58.encode58Check(SunJstcontractAddress) + "\",\""
            + Base58.encode58Check(JustMidcontractAddress) + "\"";
    logger.info("parame : "  + parame);
    String filePath = "./src/test/resources/soliditycode/TronUser.sol";
    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode").toString();
    String abi = retMap.get("abI").toString();
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName, abi, code, "constructor(address,address)"
            ,parame,"", maxFeeLimit, 0L, 100,
            null, testFoundationKey,
            testFoundationAddress, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo extn = PublicMethed
        .getTransactionInfoById(Txid, blockingStubFull).get();
    AggregatorcontractAddress = extn.getContractAddress().toByteArray();
    logger.info("contractAddress: " + Base58.encode58Check(AggregatorcontractAddress));
  }
  @Test(invocationCount = 7)
  public void test004(){
    String contractName = "Oracle";
    String parame =
        "\"" + Base58.encode58Check(SunJstcontractAddress) + "\",\""
            + Base58.encode58Check(JustMidcontractAddress) + "\"";
    logger.info("parame : "  + parame);
    String filePath = "./src/test/resources/soliditycode/TronOracles.sol";
    HashMap retMap = PublicMethed.getBycodeAbi(filePath, contractName);
    String code = retMap.get("byteCode").toString();
    String abi = retMap.get("abI").toString();
    String Txid = PublicMethed
        .deployContractWithConstantParame(contractName, abi, code, "constructor(address,address)"
            ,parame,"", maxFeeLimit, 0L, 100,
            null, testFoundationKey,
            testFoundationAddress, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo extn = PublicMethed
        .getTransactionInfoById(Txid, blockingStubFull).get();
    OraclecontractAddress = extn.getContractAddress().toByteArray();
    logger.info("contractAddress: " + Base58.encode58Check(OraclecontractAddress));
  }
  @Test(enabled = false)
  public void bn256addTest001() {
    contractAddress = Base58.decode58Check("TXoQv5dSLLXnQct5YwgUG1rrk6mXDrd3xa");
    String methodStr = "updateRequestDetails(uint128,uint128,address[],bytes32[])";
    String data = ""
        + "\"123\","
        + "\"7\","
        + "[\"TPJ6jbnqzczeZuWwJJkynBN3tPwgPSUEiA\",\"TES9EWKayP6Hs4nnbvRQ62VsPNAdsf5VAy\",\"TKVFbKooBUKENRaXeiCEG8thefkvHvhcNb\",\"TNzRm4jQcPqDaYgtxUkqpKevQRspa7ZBeD\",\"TQbizisnKDmLNdJVsSaBAW1VHCzHLUi8YG\",\"TDMUyVD5pJ4zKZLSjqt1okYZTCrvTaU4sc\",\"TRT8kRE2AcXSJbkaTbKZRKLBseC18B9WR4\"],"
        + "[\"bb347a9a63324fd995a7159cb0c8348a\",\"40691f5fd4b64ab4a5442477ed484d80\",\"f7ccb652cc254a19b0b954c49af25926\",\"38cd68072a6c4a0ca05e9b91976cf4f1\",\"328697ef599043e1a301ae985d06aabf\",\"239ff4228974435ea33f7c32cb46d297\",\"10ee483bad154f41ac58fdb4010c2c63\"]";
    logger.info("data: "  + data);
    String txid = PublicMethed
        .triggerContract(contractAddress, methodStr, data, false, 0, maxFeeLimit,
            testFoundationAddress, testFoundationKey, blockingStubFull);
    PublicMethed.waitProduceNextBlock(blockingStubFull);
    TransactionInfo option = PublicMethed
        .getTransactionInfoById(txid, blockingStubFull).get();
    long energyCost = option.getReceipt().getEnergyUsageTotal();
    logger.info("energyCost: " + energyCost);
  }
}