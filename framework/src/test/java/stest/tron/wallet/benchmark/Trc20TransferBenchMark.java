package stest.tron.wallet.benchmark;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.spongycastle.util.encoders.Hex;
import org.junit.Assert;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.crypto.ECKey;
import org.tron.common.runtime.Runtime;
import org.tron.common.runtime.TVMTestResult;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.storage.Deposit;
import org.tron.common.storage.DepositImpl;
import org.tron.common.utils.ByteUtil;
import org.tron.common.utils.Commons;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.WalletUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass;
import stest.tron.wallet.common.client.Parameter;
import stest.tron.wallet.common.client.utils.AbiUtil;
import stest.tron.wallet.common.client.utils.Base58;
import stest.tron.wallet.common.client.utils.DataWord;
import stest.tron.wallet.common.client.utils.TransactionUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@FixMethodOrder(MethodSorters.JVM)
@Slf4j
public class Trc20TransferBenchMark {
  
  
  protected static Manager manager;
  protected static TronApplicationContext context;
  protected static String dbPath;
  protected static Deposit rootDeposit;
  protected static Runtime runtime;
  
  private static byte[] trc20tokenAddress;
  private static String transferTarget = "TS7gnzsbbE72tM5n6M7foLnyLRNEhyjQKm";
  
  private static String ownerAddr = "414948C2E8A756D9437037DCD8C7E0C73D560CA38D";
  private static String ownerAddr58 = "TGehVcNhud84JDCGrNHKVz9jEAVKUpbuiv";
  private static String ownerPrikey = "cba92a516ea09f620a16ff7ee95ce0df1d56550a8babe9964981a7144c8a784a";
  private static int transferNum = 0;
  
  
  private final int sampleSize = 5000;
  private final long simulateTimes = 10;
  
  @BeforeClass
  public static void init() {
    dbPath = "output_" + Trc20TransferBenchMark.class.getName();
    Args.setParam(new String[]{"--output-directory", dbPath, "--debug"}, "config-localtest.conf");
    context = new TronApplicationContext(DefaultConfig.class);
  
    manager = context.getBean(Manager.class);
    rootDeposit = DepositImpl.createRoot(manager);
    rootDeposit.createAccount(Hex.decode(ownerAddr), Protocol.AccountType.Normal);
    rootDeposit.addBalance(Hex.decode(ownerAddr), 30000000000000L);
    
    rootDeposit.commit();
    
    ConfigLoader.disable = true;
    VMConfig.initAllowTvmTransferTrc10(1);
    VMConfig.initAllowTvmConstantinople(1);
    VMConfig.initAllowTvmSolidity059(1);
    
    trc20tokenAddress = Commons.decodeFromBase58Check("TKHBt5dqr2U9ScigACnk7hAv4kzbFAaVGU");
  }
  
  @AfterClass()
  public static void destroy() {
    Args.clearParam();
    context.destroy();
    if (FileUtil.deleteDir(new File(dbPath))) {
      logger.info("Release resources successful.");
    } else {
      logger.error("Release resources failure.");
    }
  }
  
  @Test
  public void deployTrc20()
      throws Exception{
    
    String contractName = "TronToken";
    byte[] address = Hex.decode(ownerAddr);
    String factoryCode = "60806040526100163364010000000061001b810204565b6100f8565b6100336003826"
        + "401000000006108ca61006a82021704565b604051600160a060020a038216907f6ae172837ea30b801fb"
        + "fcdd4108aa1d5bf8ff775444fd70256b44e6bf3dfc3f690600090a250565b600160a060020a038116151"
        + "561007f57600080fd5b61009282826401000000006100c1810204565b1561009c57600080fd5b600160a"
        + "060020a0316600090815260209190915260409020805460ff19166001179055565b6000600160a060020"
        + "a03821615156100d857600080fd5b50600160a060020a03166000908152602091909152604090205460f"
        + "f1690565b610990806101076000396000f3006080604052600436106100b95763ffffffff7c010000000"
        + "0000000000000000000000000000000000000000000000000600035041663095ea7b381146100be57806"
        + "318160ddd1461011057806323b872dd14610151578063395093511461019557806340c10f19146101d35"
        + "7806370a0823114610211578063983b2d561461024c5780639865027514610289578063a457c2d714610"
        + "2b8578063a9059cbb146102f6578063aa271e1a14610334578063dd62ed3e1461036f575b600080fd5b3"
        + "480156100ca57600080fd5b50d380156100d757600080fd5b50d280156100e457600080fd5b506100fc6"
        + "00160a060020a03600435166024356103b0565b604080519115158252519081900360200190f35b34801"
        + "561011c57600080fd5b50d3801561012957600080fd5b50d2801561013657600080fd5b5061013f61042"
        + "e565b60408051918252519081900360200190f35b34801561015d57600080fd5b50d3801561016a57600"
        + "080fd5b50d2801561017757600080fd5b506100fc600160a060020a03600435811690602435166044356"
        + "10434565b3480156101a157600080fd5b50d380156101ae57600080fd5b50d280156101bb57600080fd5"
        + "b506100fc600160a060020a03600435166024356104a1565b3480156101df57600080fd5b50d38015610"
        + "1ec57600080fd5b50d280156101f957600080fd5b506100fc600160a060020a036004351660243561055"
        + "1565b34801561021d57600080fd5b50d3801561022a57600080fd5b50d2801561023757600080fd5b506"
        + "1013f600160a060020a036004351661057a565b34801561025857600080fd5b50d380156102655760008"
        + "0fd5b50d2801561027257600080fd5b50610287600160a060020a0360043516610595565b005b3480156"
        + "1029557600080fd5b50d380156102a257600080fd5b50d280156102af57600080fd5b506102876105b55"
        + "65b3480156102c457600080fd5b50d380156102d157600080fd5b50d280156102de57600080fd5b50610"
        + "0fc600160a060020a03600435166024356105c0565b34801561030257600080fd5b50d3801561030f576"
        + "00080fd5b50d2801561031c57600080fd5b506100fc600160a060020a036004351660243561060b565b3"
        + "4801561034057600080fd5b50d3801561034d57600080fd5b50d2801561035a57600080fd5b506100fc6"
        + "00160a060020a0360043516610618565b34801561037b57600080fd5b50d3801561038857600080fd5b5"
        + "0d2801561039557600080fd5b5061013f600160a060020a0360043581169060243516610631565b60006"
        + "00160a060020a03831615156103c757600080fd5b336000818152600160209081526040808320600160a"
        + "060020a03881680855290835292819020869055805186815290519293927f8c5be1e5ebec7d5bd14f714"
        + "27d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925929181900390910190a350600192915050565b60025"
        + "490565b600160a060020a038316600090815260016020908152604080832033845290915281205461046"
        + "8908363ffffffff61065c16565b600160a060020a0385166000908152600160209081526040808320338"
        + "452909152902055610497848484610673565b5060019392505050565b6000600160a060020a038316151"
        + "56104b857600080fd5b336000908152600160209081526040808320600160a060020a038716845290915"
        + "29020546104ec908363ffffffff61074016565b336000818152600160209081526040808320600160a06"
        + "0020a0389168085529083529281902085905580519485525191937f8c5be1e5ebec7d5bd14f71427d1e8"
        + "4f3dd0314c0f7b2291e5b200ac8c7c3b925929081900390910190a350600192915050565b600061055c3"
        + "3610618565b151561056757600080fd5b6105718383610759565b50600192915050565b600160a060020"
        + "a031660009081526020819052604090205490565b61059e33610618565b15156105a957600080fd5b610"
        + "5b281610803565b50565b6105be3361084b565b565b6000600160a060020a03831615156105d75760008"
        + "0fd5b336000908152600160209081526040808320600160a060020a03871684529091529020546104ec9"
        + "08363ffffffff61065c16565b6000610571338484610673565b600061062b60038363ffffffff6108931"
        + "6565b92915050565b600160a060020a03918216600090815260016020908152604080832093909416825"
        + "291909152205490565b6000808383111561066c57600080fd5b5050900390565b600160a060020a03821"
        + "6151561068857600080fd5b600160a060020a0383166000908152602081905260409020546106b190826"
        + "3ffffffff61065c16565b600160a060020a0380851660009081526020819052604080822093909355908"
        + "416815220546106e6908263ffffffff61074016565b600160a060020a038084166000818152602081815"
        + "260409182902094909455805185815290519193928716927fddf252ad1be2c89b69c2b068fc378daa952"
        + "ba7f163c4a11628f55a4df523b3ef92918290030190a3505050565b60008282018381101561075257600"
        + "080fd5b9392505050565b600160a060020a038216151561076e57600080fd5b600254610781908263fff"
        + "fffff61074016565b600255600160a060020a0382166000908152602081905260409020546107ad90826"
        + "3ffffffff61074016565b600160a060020a0383166000818152602081815260408083209490945583518"
        + "581529351929391927fddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef9"
        + "281900390910190a35050565b61081460038263ffffffff6108ca16565b604051600160a060020a03821"
        + "6907f6ae172837ea30b801fbfcdd4108aa1d5bf8ff775444fd70256b44e6bf3dfc3f690600090a250565"
        + "b61085c60038263ffffffff61091816565b604051600160a060020a038216907fe94479a9f7e1952cc78"
        + "f2d6baab678adc1b772d936c6583def489e524cb6669290600090a250565b6000600160a060020a03821"
        + "615156108aa57600080fd5b50600160a060020a03166000908152602091909152604090205460ff16905"
        + "65b600160a060020a03811615156108df57600080fd5b6108e98282610893565b156108f357600080fd5"
        + "b600160a060020a0316600090815260209190915260409020805460ff19166001179055565b600160a06"
        + "0020a038116151561092d57600080fd5b6109378282610893565b151561094257600080fd5b600160a06"
        + "0020a0316600090815260209190915260409020805460ff191690555600a165627a7a72305820b3c9ea2"
        + "cf09e2e2c1eae14b77c733a670762c25b534f1b3cc31e24ee05d3e29e0029";
    String abi = "[{\"constant\":false,\"inputs\":[{\"name\":\"spender\",\"type\":\"address\"},"
        + "{\"name\":\"value\",\"type\":\"uint256\"}],\"name\":\"approve\",\"outputs\":[{\"name"
        + "\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"t"
        + "ype\":\"function\"},{\"constant\":true,\"inputs\":[],\"name\":\"totalSupply\",\"outp"
        + "uts\":[{\"name\":\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\""
        + "view\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"from\",\"t"
        + "ype\":\"address\"},{\"name\":\"to\",\"type\":\"address\"},{\"name\":\"value\",\"type"
        + "\":\"uint256\"}],\"name\":\"transferFrom\",\"outputs\":[{\"name\":\"\",\"type\":\"bo"
        + "ol\"}],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{"
        + "\"constant\":false,\"inputs\":[{\"name\":\"spender\",\"type\":\"address\"},{\"name\""
        + ":\"addedValue\",\"type\":\"uint256\"}],\"name\":\"increaseAllowance\",\"outputs\":[{"
        + "\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpayable"
        + "\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"to\",\"type\":"
        + "\"address\"},{\"name\":\"value\",\"type\":\"uint256\"}],\"name\":\"mint\",\"outputs\""
        + ":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"nonpaya"
        + "ble\",\"type\":\"function\"},{\"constant\":true,\"inputs\":[{\"name\":\"owner\",\"ty"
        + "pe\":\"address\"}],\"name\":\"balanceOf\",\"outputs\":[{\"name\":\"\",\"type\":\"uin"
        + "t256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function\"},{\"co"
        + "nstant\":false,\"inputs\":[{\"name\":\"account\",\"type\":\"address\"}],\"name\":\"a"
        + "ddMinter\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"typ"
        + "e\":\"function\"},{\"constant\":false,\"inputs\":[],\"name\":\"renounceMinter\",\"ou"
        + "tputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\""
        + "},{\"constant\":false,\"inputs\":[{\"name\":\"spender\",\"type\":\"address\"},{\"nam"
        + "e\":\"subtractedValue\",\"type\":\"uint256\"}],\"name\":\"decreaseAllowance\",\"outp"
        + "uts\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"no"
        + "npayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"to\",\""
        + "type\":\"address\"},{\"name\":\"value\",\"type\":\"uint256\"}],\"name\":\"transfer\""
        + ",\"outputs\":[{\"name\":\"\",\"type\":\"bool\"}],\"payable\":false,\"stateMutability"
        + "\":\"nonpayable\",\"type\":\"function\"},{\"constant\":true,\"inputs\":[{\"name\":\""
        + "account\",\"type\":\"address\"}],\"name\":\"isMinter\",\"outputs\":[{\"name\":\"\",\""
        + "type\":\"bool\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":\"function"
        + "\"},{\"constant\":true,\"inputs\":[{\"name\":\"owner\",\"type\":\"address\"},{\"name"
        + "\":\"spender\",\"type\":\"address\"}],\"name\":\"allowance\",\"outputs\":[{\"name\":"
        + "\"\",\"type\":\"uint256\"}],\"payable\":false,\"stateMutability\":\"view\",\"type\":"
        + "\"function\"},{\"anonymous\":false,\"inputs\":[{\"indexed\":true,\"name\":\"account\""
        + ",\"type\":\"address\"}],\"name\":\"MinterAdded\",\"type\":\"event\"},{\"anonymous\":"
        + "false,\"inputs\":[{\"indexed\":true,\"name\":\"account\",\"type\":\"address\"}],\"na"
        + "me\":\"MinterRemoved\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"index"
        + "ed\":true,\"name\":\"from\",\"type\":\"address\"},{\"indexed\":true,\"name\":\"to\","
        + "\"type\":\"address\"},{\"indexed\":false,\"name\":\"value\",\"type\":\"uint256\"}],\""
        + "name\":\"Transfer\",\"type\":\"event\"},{\"anonymous\":false,\"inputs\":[{\"indexed\""
        + ":true,\"name\":\"owner\",\"type\":\"address\"},{\"indexed\":true,\"name\":\"spender\""
        + ",\"type\":\"address\"},{\"indexed\":false,\"name\":\"value\",\"type\":\"uint256\"}],"
        + "\"name\":\"Approval\",\"type\":\"event\"}]";
    Wallet.setAddressPreFixByte(Parameter.CommonConstant.ADD_PRE_FIX_BYTE_MAINNET);
    long value = 123456;
    long fee = 100000000;
    long consumeUserResourcePercent = 0;
    
    // deploy contract
    Protocol.Transaction tx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        contractName, address, abi, factoryCode, value, fee, consumeUserResourcePercent,
        null);
    trc20tokenAddress = WalletUtil.generateContractAddress(tx);
    logger.info("contractAddress: " + Base58.encode58Check(trc20tokenAddress));
    runtime = TvmTestUtils.processTransactionAndReturnRuntime(tx, rootDeposit, null);
    Assert.assertNull(runtime.getRuntimeError());
  
  }
  
  @Test
  public void mint() throws Exception{
    long fee = 100000000;
    String methodByAddr = "mint(address,uint256)";
    List<Object> params = Arrays.asList(ownerAddr58,"1000000");
    TVMTestResult result = TvmTestUtils
        .triggerContractAndReturnTvmTestResult(Hex.decode(ownerAddr),
            trc20tokenAddress, Hex.decode(AbiUtil.parseMethod(methodByAddr, params)),
            0, fee, manager, null);
    Assert.assertNull(result.getRuntime().getRuntimeError());
    Assert.assertEquals(Hex.toHexString(result.getRuntime().getResult().getHReturn()),
        "0000000000000000000000000000000000000000000000000000000000000001");
  }
  
  @Test
  public void transferTest() throws Exception{
    long fee = 100000000;
    String methodByAddr;
    List<Object> params;
    TVMTestResult result;
  
    methodByAddr = "transfer(address,uint256)";
    params = Arrays.asList(transferTarget,"10");
  
    // build transaction
    SmartContractOuterClass.TriggerSmartContract contract = TvmTestUtils
        .buildTriggerSmartContract(Hex.decode(ownerAddr),
            trc20tokenAddress, Hex.decode(AbiUtil.parseMethod(methodByAddr, params)),0);
    TransactionCapsule trxCapWithoutFeeLimit = new TransactionCapsule(contract,
        Protocol.Transaction.Contract.ContractType.TriggerSmartContract);
    Protocol.Transaction.Builder transactionBuilder = trxCapWithoutFeeLimit.getInstance().toBuilder();
    Protocol.Transaction.raw.Builder rawBuilder = trxCapWithoutFeeLimit.getInstance().getRawData()
        .toBuilder();
    rawBuilder.setFeeLimit(fee);
    rawBuilder.setRefBlockHash(ByteString.copyFrom(ByteUtil.hexToBytes("19b59068c6058ff4")));
    rawBuilder.setRefBlockBytes(ByteString.copyFrom(new byte[]{0x00, 0x00}));
    rawBuilder.setRefBlockNum(0);
    rawBuilder.setExpiration(60000);
    transactionBuilder.setRawData(rawBuilder);

    BigInteger priK = new BigInteger(ownerPrikey, 16);
    ECKey ecKey = ECKey.fromPrivate(priK);
    
    long start = System.nanoTime();
    int sampleSize = 10;
    for(int i = 0; i<sampleSize; i++) {
      //sign transaction
      Protocol.Transaction trx = transactionBuilder.build();
      trx = TransactionUtils.setTimestamp(trx);
      trx = TransactionUtils.sign(trx, ecKey);
      
      manager.processTransaction(new TransactionCapsule(trx), null);
    }
    long timeRun = System.nanoTime() - start;
    logger.info("[benchmark] timeRun = {}", timeRun);
    transferNum += sampleSize * 10;
    
    

    methodByAddr = "balanceOf(address)";
    params = Arrays.asList(transferTarget);
    result = TvmTestUtils
        .triggerContractAndReturnTvmTestResult(Hex.decode(ownerAddr),
            trc20tokenAddress, Hex.decode(AbiUtil.parseMethod(methodByAddr, params)),
            0, fee, manager, null);
    Assert.assertNull(result.getRuntime().getRuntimeError());
    
    Assert.assertEquals(Hex.toHexString(result.getRuntime().getResult().getHReturn()),
        new DataWord(transferNum).toHexString());
    logger.info("[benchmark] balanceof:{}", Hex.toHexString(result.getRuntime().getResult().getHReturn()));
  }
  
  @Test
  public void benchmarkTransfer() throws Exception{
    
    long fee = 100000000;
    String methodByAddr;
    List<Object> params;
  
    methodByAddr = "transfer(address,uint256)";
    params = Arrays.asList(transferTarget,"10");
  
    // build transaction
    SmartContractOuterClass.TriggerSmartContract contract = TvmTestUtils
        .buildTriggerSmartContract(Hex.decode(ownerAddr),
            trc20tokenAddress, Hex.decode(AbiUtil.parseMethod(methodByAddr, params)),0);
    TransactionCapsule trxCapWithoutFeeLimit = new TransactionCapsule(contract,
        Protocol.Transaction.Contract.ContractType.TriggerSmartContract);
    Protocol.Transaction.Builder transactionBuilder = trxCapWithoutFeeLimit.getInstance().toBuilder();
    Protocol.Transaction.raw.Builder rawBuilder = trxCapWithoutFeeLimit.getInstance().getRawData()
        .toBuilder();
    rawBuilder.setFeeLimit(fee);
    rawBuilder.setRefBlockHash(ByteString.copyFrom(ByteUtil.hexToBytes("19b59068c6058ff4")));
    rawBuilder.setRefBlockBytes(ByteString.copyFrom(new byte[]{0x00, 0x00}));
    rawBuilder.setRefBlockNum(0);
    rawBuilder.setExpiration(60000);
    transactionBuilder.setRawData(rawBuilder);
  
    BigInteger priK = new BigInteger(ownerPrikey, 16);
    ECKey ecKey = ECKey.fromPrivate(priK);
  
    BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter("Trc20TransferBenchMark.csv", true));
  
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss S");
    for(int i = 1; i <= simulateTimes; i++) {
      long timeAll = 0;
      long stepCount = 0;
  
      while (stepCount < sampleSize) {
        //sign transaction
        Protocol.Transaction trx = transactionBuilder.build();
        trx = TransactionUtils.setTimestamp(trx);
        trx = TransactionUtils.sign(trx, ecKey);
    
        long startTime = System.nanoTime();
    
        manager.processTransaction(new TransactionCapsule(trx), null);
    
        long endTime = System.nanoTime();
        long runTime = endTime - startTime;
        timeAll += runTime;
        stepCount++;
      }
  
      double singleTime = ((double) timeAll) / stepCount;
      String msg = String.format("\"%s\",%d,%d,%f", sdf.format(new Date()), timeAll, stepCount, singleTime);
      System.out.println(msg);
      bufferedWriter.write(msg + "\n");
      bufferedWriter.flush();
    }
    bufferedWriter.close();
  }
}
