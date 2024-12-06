package org.tron.program;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.beust.jcommander.JCommander;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.api.GrpcAPI;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Commons;
import org.tron.core.ChainBaseManager;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.utils.FastByteComparisons;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.http.FullNodeHttpApiService;
import org.tron.core.services.interfaceJsonRpcOnPBFT.JsonRpcServiceOnPBFT;
import org.tron.core.services.interfaceJsonRpcOnSolidity.JsonRpcServiceOnSolidity;
import org.tron.core.services.interfaceOnPBFT.RpcApiServiceOnPBFT;
import org.tron.core.services.interfaceOnPBFT.http.PBFT.HttpApiOnPBFTService;
import org.tron.core.services.interfaceOnSolidity.RpcApiServiceOnSolidity;
import org.tron.core.services.interfaceOnSolidity.http.solidity.HttpApiOnSolidityService;
import org.tron.core.services.jsonrpc.FullNodeJsonRpcHttpService;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.SmartContractOuterClass;

@Slf4j(topic = "app")
public class FullNode {


  public static void load(String path) {
    try {
      File file = new File(path);
      if (!file.exists() || !file.isFile() || !file.canRead()) {
        return;
      }
      LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
      JoranConfigurator configurator = new JoranConfigurator();
      configurator.setContext(lc);
      lc.reset();
      configurator.doConfigure(file);
    } catch (Exception e) {
      logger.error(e.getMessage());
    }
  }

  /**
   * Start the FullNode.
   */
  public static void main(String[] args) throws Exception {
    logger.info("Full node running.");
    Args.setParam(args, Constant.TESTNET_CONF);
    CommonParameter parameter = Args.getInstance();

    load(parameter.getLogbackPath());

    if (parameter.isHelp()) {
      JCommander jCommander = JCommander.newBuilder().addObject(Args.PARAMETER).build();
      jCommander.parse(args);
      Args.printHelp(jCommander);
      return;
    }

    if (Args.getInstance().isDebug()) {
      logger.info("in debug mode, it won't check energy time");
    } else {
      logger.info("not in debug mode, it will check energy time");
    }

    // init metrics first
    Metrics.init();

    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.setAllowCircularReferences(false);
    TronApplicationContext context =
        new TronApplicationContext(beanFactory);
    context.register(DefaultConfig.class);
    context.refresh();
    Application appT = ApplicationFactory.create(context);
    context.registerShutdownHook();

    // grpc api server
    RpcApiService rpcApiService = context.getBean(RpcApiService.class);
    appT.addService(rpcApiService);

    // http api server
    FullNodeHttpApiService httpApiService = context.getBean(FullNodeHttpApiService.class);
    if (CommonParameter.getInstance().fullNodeHttpEnable) {
      appT.addService(httpApiService);
    }

    // JSON-RPC http server
    if (CommonParameter.getInstance().jsonRpcHttpFullNodeEnable) {
      FullNodeJsonRpcHttpService jsonRpcHttpService =
          context.getBean(FullNodeJsonRpcHttpService.class);
      appT.addService(jsonRpcHttpService);
    }

    // full node and solidity node fuse together
    // provide solidity rpc and http server on the full node.
    RpcApiServiceOnSolidity rpcApiServiceOnSolidity = context
        .getBean(RpcApiServiceOnSolidity.class);
    appT.addService(rpcApiServiceOnSolidity);
    HttpApiOnSolidityService httpApiOnSolidityService = context
        .getBean(HttpApiOnSolidityService.class);
    if (CommonParameter.getInstance().solidityNodeHttpEnable) {
      appT.addService(httpApiOnSolidityService);
    }

    // JSON-RPC on solidity
    if (CommonParameter.getInstance().jsonRpcHttpSolidityNodeEnable) {
      JsonRpcServiceOnSolidity jsonRpcServiceOnSolidity = context
          .getBean(JsonRpcServiceOnSolidity.class);
      appT.addService(jsonRpcServiceOnSolidity);
    }

    // PBFT API (HTTP and GRPC)
    RpcApiServiceOnPBFT rpcApiServiceOnPBFT = context
        .getBean(RpcApiServiceOnPBFT.class);
    appT.addService(rpcApiServiceOnPBFT);
    HttpApiOnPBFTService httpApiOnPBFTService = context
        .getBean(HttpApiOnPBFTService.class);
    appT.addService(httpApiOnPBFTService);

    // JSON-RPC on PBFT
    if (CommonParameter.getInstance().jsonRpcHttpPBFTNodeEnable) {
      JsonRpcServiceOnPBFT jsonRpcServiceOnPBFT = context.getBean(JsonRpcServiceOnPBFT.class);
      appT.addService(jsonRpcServiceOnPBFT);
    }
//    appT.startup();
//    appT.blockUntilShutdown();

    Set<String> fakeUSDTAddresses = readAddressesFromFile("/data/contract.txt");
    Set<String> safeAddresses = readAddressesFromFile("/data/safe.txt");
    long startNum = 51500000;
//    long startNum = 61000000;
    long endNum = ChainBaseManager.getInstance().getHeadBlockNum();
    Wallet wallet = context.getBean(Wallet.class);
    Map<String, User> users = new HashMap<>();
    String currentDate = "";
    byte[] USDT = Hex.decode("41a614f803B6FD780986A42c78Ec9c7f77e6DeD13C");
    byte[] TOPIC = Hex.decode("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    for (long i = startNum; i < endNum; i++) {
      Protocol.Block block = wallet.getBlockByNum(i);
      GrpcAPI.TransactionInfoList infoList = wallet.getTransactionInfoByBlockNum(i);

      String date = new SimpleDateFormat("yyyy-MM-dd")
          .format(new Date(block.getBlockHeader().getRawData().getTimestamp()));
      if (!currentDate.equals(date)) {
        long activeTotal = 0, realActiveTotal = 0, activeFrom = 0, realActiveFrom = 0, activeTo = 0, realActiveTo = 0;
        for (Map.Entry<String, User> e : users.entrySet()) {
          User user = e.getValue();
          activeTotal += 1;
          if (!user.is10Phisher && !user.isFakeUSDTSender && (user.useFee || user.hasBig || !user.hasSmall)) {
            realActiveTotal += 1;
          }

          if (user.activeFrom) {
            activeFrom += 1;
            if (!user.is10Phisher && !user.isFakeUSDTSender && (user.useFee || user.hasBig || !user.hasSmall)) {
              realActiveFrom += 1;
            }
          }

          if (user.activeTo) {
            activeTo += 1;
            if (!user.is10Phisher && !user.isFakeUSDTSender && (user.useFee || user.hasBig || !user.hasSmall)) {
              realActiveTo += 1;
            }
          }
        }
        System.out.printf("%s %d %d %d %d %d %d\n", currentDate, activeTotal,
            realActiveTotal, activeFrom, realActiveFrom, activeTo, realActiveTo);
        currentDate = date;
        users = new HashMap<>();
      }

      for (int j = 0; j < block.getTransactionsCount(); j++) {
        Protocol.Transaction tx = block.getTransactions(j);
        Protocol.TransactionInfo info = infoList.getTransactionInfo(j);

        TransactionCapsule txCap = new TransactionCapsule(tx);
        String from = Hex.toHexString(txCap.getOwnerAddress());
        if (!users.containsKey(from)) {
          users.put(from, new User());
        }
        users.get(from).activeFrom = true;
        if (info.getFee() > 0) {
            users.get(from).useFee = true;
        }
        String to;
        Protocol.Transaction.Contract contract = tx.getRawData().getContract(0);
        switch (contract.getType()) {
          case TransferContract:
            BalanceContract.TransferContract tc = contract.getParameter()
                .unpack(BalanceContract.TransferContract.class);
            to = Hex.toHexString(tc.getToAddress().toByteArray());
            if (!users.containsKey(to)) {
              users.put(to, new User());
            }
            users.get(to).activeTo = true;

            if (tc.getAmount() < 10) {
              users.get(from).hasSmall = true;
            }

            if (tc.getAmount() > 100_000_000L) {
              users.get(from).hasBig = true;
            }
            break;
          case TransferAssetContract:
            to = Hex.toHexString(contract.getParameter()
                .unpack(AssetIssueContractOuterClass.TransferAssetContract.class).getToAddress().toByteArray());
            if (!users.containsKey(to)) {
              users.put(to, new User());
            }
            users.get(to).activeTo = true;
            users.get(from).is10Phisher = true;
            break;
          case DelegateResourceContract:
            to = Hex.toHexString(contract.getParameter()
                .unpack(BalanceContract.DelegateResourceContract.class).getReceiverAddress().toByteArray());
            if (!users.containsKey(to)) {
              users.put(to, new User());
            }
            users.get(to).activeTo = true;
            break;
          case UnDelegateResourceContract:
            to = Hex.toHexString(contract.getParameter()
                .unpack(BalanceContract.UnDelegateResourceContract.class).getReceiverAddress().toByteArray());
            if (!users.containsKey(to)) {
              users.put(to, new User());
            }
            users.get(to).activeTo = true;
            break;
          case TriggerSmartContract:
            String contractAddress = Hex.toHexString(info.getContractAddress().toByteArray());

            if (!safeAddresses.contains(contractAddress)) {
              if (fakeUSDTAddresses.contains(contractAddress)) {
                users.get(from).isFakeUSDTSender = true;
              } else {
                if (isFakeUSDT(contractAddress, wallet)) {
                  fakeUSDTAddresses.add(contractAddress);
                  users.get(from).isFakeUSDTSender = true;
                } else {
                  safeAddresses.add(contractAddress);
                }
              }
            }

            if (info.getResult() == Protocol.TransactionInfo.code.SUCESS
                && FastByteComparisons.equalByte(info.getContractAddress().toByteArray(), USDT)
                && info.getLogCount() == 1
                && FastByteComparisons.equalByte(info.getLog(0).getTopics(0).toByteArray(), TOPIC)) {
              BigInteger amount = new BigInteger(info.getLog(0).getData().toByteArray());
              from = Hex.toHexString(Arrays.copyOfRange(info.getLog(0).getTopics(1).toByteArray(), 12, 32));
              from = "41" + from;
              to = Hex.toHexString(Arrays.copyOfRange(info.getLog(0).getTopics(2).toByteArray(), 12, 32));
              to = "41" + to;

              if (!users.containsKey(from)) {
                users.put(from, new User());
              }
              users.get(from).activeFrom = true;

              if (!users.containsKey(to)) {
                  users.put(to, new User());
              }
              users.get(to).activeTo = true;

              if (amount.compareTo(BigInteger.valueOf(100_000)) < 0) {
                users.get(from).hasSmall = true;
              }

              if (amount.compareTo(BigInteger.valueOf(10_000_000L)) > 0) {
                users.get(from).hasBig = true;
              }
            }
        }
      }
    }
  }

  public static Set<String> readAddressesFromFile(String filePath) {
    Set<String> addresses = new HashSet<>();
    try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
      String line;
      while ((line = br.readLine()) != null) {
        byte[] address = Commons.decode58Check(line);
        if (address != null) {
          addresses.add(Hex.toHexString(address));
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return addresses;
  }

  private static boolean isFakeUSDT(String contractAddress, Wallet wallet) throws Exception {
    String name = "";
    byte[] nameBytes = triggerConstant(contractAddress, "06fdde03", wallet);
    if (nameBytes != null && nameBytes.length >= 3 * 32) {
      name = new String(nameBytes, 2 * 32,
          new DataWord(Arrays.copyOfRange(nameBytes, 32, 2 * 32)).intValue()).toUpperCase();

    }

    String symbol = "";
    byte[] symbolBytes = triggerConstant(contractAddress, "95d89b41", wallet);
    if (symbolBytes != null && symbolBytes.length >= 3 * 32) {
      symbol = new String(symbolBytes, 2 * 32,
          new DataWord(Arrays.copyOfRange(symbolBytes, 32, 2 * 32)).intValue()).toUpperCase();
    }

    if ((name.contains("U") && name.contains("S") && name.contains("D"))
      || (symbol.contains("U") && symbol.contains("S") && symbol.contains("D"))) {
      logger.info("Fake USDT: {} {} {}", contractAddress, name, symbol);
      return true;
    }

    return false;
  }

  private static byte[] triggerConstant(String contractAddress, String selector, Wallet wallet) throws Exception {
    SmartContractOuterClass.TriggerSmartContract contract =
        SmartContractOuterClass.TriggerSmartContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(contractAddress)))
            .setContractAddress(ByteString.copyFrom(ByteArray.fromHexString(contractAddress)))
            .setData(ByteString.copyFrom(ByteArray.fromHexString(selector)))
            .build();
    TransactionCapsule trxCap = wallet.createTransactionCapsule(contract,
        Protocol.Transaction.Contract.ContractType.TriggerSmartContract);

    GrpcAPI.TransactionExtention.Builder trxExtBuilder = GrpcAPI.TransactionExtention.newBuilder();
    GrpcAPI.Return.Builder retBuilder = GrpcAPI.Return.newBuilder();

    Protocol.Transaction tx = wallet.triggerConstantContract(contract, trxCap, trxExtBuilder, retBuilder);

    if (tx.getRet(0).getRet().equals(Protocol.Transaction.Result.code.SUCESS)) {
      return trxExtBuilder.getConstantResultCount() > 0 ? trxExtBuilder.getConstantResult(0).toByteArray() : null;
    }

    return null;
  }
}

class User {
  boolean activeFrom;
  boolean activeTo;
  boolean useFee;
  boolean hasBig;
  boolean hasSmall;
  boolean is10Phisher;
  boolean isFakeUSDTSender;
}

