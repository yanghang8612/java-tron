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
import org.tron.common.utils.StringUtil;
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

    Set<ByteString> fakeUSDTAddresses = readAddressesFromFile("/data/contract.txt");
    Set<ByteString> safeAddresses = readAddressesFromFile("/data/safe.txt");
    long startNum = 51500000;
//    long startNum = 61000000;
    long endNum = ChainBaseManager.getInstance().getHeadBlockNum();
    Wallet wallet = context.getBean(Wallet.class);

    long[][] SegUSDTStats = new long[9][3];
    long[] trxStats = new long[3];
    long[] trc10Stats = new long[3];
    long[] delegateStats = new long[3];
    long[] smallUSDTStats = new long[3];
    long[] USDTStats = new long[3];
    long[] SunSwapV2Stats = new long[3];
    long[] SunSwapV3Stats = new long[3];
    long[] SunPumpStats = new long[3];
    long[] otherContractStats = new long[3];
    long[] otherStats = new long[3];

    Map<ByteString, Integer> contracts = new HashMap<>();
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TKzxdSv2FZKQrEqkKVgp5DcwEXBEKMg2Ax")), 0);
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TXF1xDbVGdxFGbovmmmXvBGu8ZiE3Lq4mR")), 0);

    contracts.put(ByteString.copyFrom(Commons.decode58Check("TFVisXFaijZfeyeSjCEVkHfex7HGdTxzF9")), 1);
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TJ4NNy8xZEqsowCBhLvZ45LCqPdGjkET5j")), 1);

    contracts.put(ByteString.copyFrom(Commons.decode58Check("TG9nDZMUtC4LBmrWSdNXNi8xrKzXTMMSKT")), 2);
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TQHj5QZA8PaHBcAGkYdi8QxdtuNabuVx5r")), 2);
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TTfvyrAz86hbZk5iDpKD78pqLGgi8C7AAw")), 2);
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB")), 2);
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TSiiYf1b1PV1fpT9T7V4wy11btsNVajw1g")), 2);
    contracts.put(ByteString.copyFrom(Commons.decode58Check("TRs4TG1vizrtkVXAM1CsuWvxtXat3J7nTu")), 2);

    Map<ByteString, User> users = new HashMap<>();
    String currentDate = "";
    long energyPrice = 420;

    byte[] USDT = Hex.decode("41a614f803B6FD780986A42c78Ec9c7f77e6DeD13C");
    byte[] TOPIC = Hex.decode("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");

    for (long i = startNum; i < endNum; i++) {
      Protocol.Block block = wallet.getBlockByNum(i);
      GrpcAPI.TransactionInfoList infoList = wallet.getTransactionInfoByBlockNum(i);

      if (block.getBlockHeader().getRawData().getTimestamp() >= 1726747200000L) {
        energyPrice = 210;
      }

      String date = new SimpleDateFormat("yyyy-MM-dd")
          .format(new Date(block.getBlockHeader().getRawData().getTimestamp()));
      if (!currentDate.equals(date)) {
        long activeTotal = 0, realActiveTotal = 0, activeFrom = 0, realActiveFrom = 0, activeTo = 0, realActiveTo = 0;
        for (Map.Entry<ByteString, User> e : users.entrySet()) {
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
        System.out.printf("Active %s %d %d %d %d %d %d\n", currentDate,
            activeTotal, realActiveTotal, activeFrom, realActiveFrom, activeTo, realActiveTo);
        System.out.printf("USDT %s %s %s %s %s %s %s %s %s %s\n", currentDate,
            format(SegUSDTStats[0]), format(SegUSDTStats[1]), format(SegUSDTStats[2]), format(SegUSDTStats[3]),
            format(SegUSDTStats[4]), format(SegUSDTStats[5]), format(SegUSDTStats[6]), format(SegUSDTStats[7]),
            format(SegUSDTStats[8]));
        System.out.printf("Revenue %s %s %s %s %s %s %s %s %s %s %s\n", currentDate,
            format(trxStats), format(trc10Stats), format(delegateStats), format(smallUSDTStats), format(USDTStats),
            format(SunSwapV2Stats), format(SunSwapV3Stats), format(SunPumpStats), format(otherContractStats), format(otherStats));
        currentDate = date;
        SegUSDTStats = new long[9][3];
        trxStats = new long[3];
        trc10Stats = new long[3];
        delegateStats = new long[3];
        smallUSDTStats = new long[3];
        USDTStats = new long[3];
        SunSwapV2Stats = new long[3];
        SunSwapV3Stats = new long[3];
        SunPumpStats = new long[3];
        otherContractStats = new long[3];
        otherStats = new long[3];
        users = new HashMap<>();
      }

      for (int j = 0; j < block.getTransactionsCount(); j++) {
        Protocol.Transaction tx = block.getTransactions(j);
        Protocol.TransactionInfo info = infoList.getTransactionInfo(j);

        TransactionCapsule txCap = new TransactionCapsule(tx);
        ByteString from = ByteString.copyFrom(txCap.getOwnerAddress());
        if (!users.containsKey(from)) {
          users.put(from, new User());
        }
        users.get(from).activeFrom = true;
        if (info.getFee() > 0) {
            users.get(from).useFee = true;
        }
        ByteString to;
        Protocol.Transaction.Contract contract = tx.getRawData().getContract(0);
        switch (contract.getType()) {
          case TransferContract:
            // Active address stats
            BalanceContract.TransferContract tc = contract.getParameter()
                .unpack(BalanceContract.TransferContract.class);
            to = tc.getToAddress();
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

            // Revenue stats
            trxStats[0] += 1;
            trxStats[1] += info.getFee();
            trxStats[2] += info.getReceipt().getNetUsage() * 1000;
            break;
          case TransferAssetContract:
            // Active address stats
            to = contract.getParameter()
                .unpack(AssetIssueContractOuterClass.TransferAssetContract.class).getToAddress();
            if (!users.containsKey(to)) {
              users.put(to, new User());
            }
            users.get(to).activeTo = true;
            users.get(from).is10Phisher = true;

            // Revenue stats
            trc10Stats[0] += 1;
            trc10Stats[1] += info.getFee();
            trc10Stats[2] += info.getReceipt().getNetUsage() * 1000;
            break;
          case DelegateResourceContract:
            // Active address stats
            to = contract.getParameter()
                .unpack(BalanceContract.DelegateResourceContract.class).getReceiverAddress();
            if (!users.containsKey(to)) {
              users.put(to, new User());
            }
            users.get(to).activeTo = true;

            // Revenue stats
            delegateStats[0] += 1;
            delegateStats[1] += info.getFee();
            delegateStats[2] += info.getReceipt().getNetUsage() * 1000;
            break;
          case UnDelegateResourceContract:
            // Active address stats
            to = contract.getParameter()
                .unpack(BalanceContract.UnDelegateResourceContract.class).getReceiverAddress();
            if (!users.containsKey(to)) {
              users.put(to, new User());
            }
            users.get(to).activeTo = true;

            // Revenue stats
            delegateStats[0] += 1;
            delegateStats[1] += info.getFee();
            delegateStats[2] += info.getReceipt().getNetUsage() * 1000;
            break;
          case TriggerSmartContract:
            ByteString contractAddress = info.getContractAddress();

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

            long stakingRevenue = info.getReceipt().getNetUsage() * 1000
                + (info.getReceipt().getEnergyUsage() + info.getReceipt().getOriginEnergyUsage()) * energyPrice;

            boolean isUSDT = FastByteComparisons.equalByte(info.getContractAddress().toByteArray(), USDT);

            if (isUSDT && info.getResult() == Protocol.TransactionInfo.code.SUCESS
                && info.getLogCount() == 1
                && FastByteComparisons.equalByte(info.getLog(0).getTopics(0).toByteArray(), TOPIC)) {
              BigInteger amount = new BigInteger(info.getLog(0).getData().toByteArray());
              from = ByteString.copyFrom(new byte[]{0x41}).concat(info.getLog(0).getTopics(1).substring(12));
              to = ByteString.copyFrom(new byte[]{0x41}).concat(info.getLog(0).getTopics(2).substring(12));

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
              } else if (amount.compareTo(BigInteger.valueOf(10_000_000L)) > 0) {
                users.get(from).hasBig = true;
              }

              if (amount.compareTo(BigInteger.valueOf(500_000L)) <= 0) {
                SegUSDTStats[0][0] += 1;
                SegUSDTStats[0][1] += info.getFee();
                SegUSDTStats[0][2] += stakingRevenue;

                smallUSDTStats[0] += 1;
                smallUSDTStats[1] += info.getFee();
                smallUSDTStats[2] += stakingRevenue;
              } else {
                int amountLen = amount.toString().length();
                if (amountLen < 13) {
                  SegUSDTStats[amountLen - 5][0] += 1;
                  SegUSDTStats[amountLen - 5][1] += info.getFee();
                  SegUSDTStats[amountLen - 5][2] += stakingRevenue;
                } else {
                  SegUSDTStats[8][0] += 1;
                  SegUSDTStats[8][1] += info.getFee();
                  SegUSDTStats[8][2] += stakingRevenue;
                }

                USDTStats[0] += 1;
                USDTStats[1] += info.getFee();
                USDTStats[2] += stakingRevenue;
              }
            }

            if (!isUSDT) {
              otherContractStats[0] += 1;
              otherContractStats[1] += info.getFee();
              otherContractStats[2] += stakingRevenue;

              for(Protocol.TransactionInfo.Log log : info.getLogList()) {
                ByteString address = ByteString.copyFrom(new byte[]{0x41}).concat(log.getAddress());
                if (!fakeUSDTAddresses.contains(address) && log.getTopicsCount() == 3 && log.getData().size() == 32
                    && FastByteComparisons.equalByte(log.getTopics(0).toByteArray(), TOPIC)) {
                  from = ByteString.copyFrom(new byte[]{0x41}).concat(log.getTopics(1).substring(12));
                  to = ByteString.copyFrom(new byte[]{0x41}).concat(log.getTopics(2).substring(12));

                  if (!users.containsKey(from)) {
                    users.put(from, new User());
                  }
                  users.get(from).activeFrom = true;

                  if (!users.containsKey(to)) {
                    users.put(to, new User());
                  }
                  users.get(to).activeTo = true;
                }
              }
            }

            if (contracts.containsKey(info.getContractAddress())) {
              int type = contracts.get(info.getContractAddress());
              if (type == 0) {
                SunSwapV2Stats[0] += 1;
                SunSwapV2Stats[1] += info.getFee();
                SunSwapV2Stats[2] += stakingRevenue;
              } else if (type == 1) {
                SunSwapV3Stats[0] += 1;
                SunSwapV3Stats[1] += info.getFee();
                SunSwapV3Stats[2] += stakingRevenue;
              } else if (type == 2) {
                SunPumpStats[0] += 1;
                SunPumpStats[1] += info.getFee();
                SunPumpStats[2] += stakingRevenue;
              }
            }
            break;
          default:
            otherStats[0] += 1;
            otherStats[1] += info.getFee();
            otherStats[2] += info.getReceipt().getNetUsage() * 1000;
        }
      }
    }
  }

  private static String format(long[] stats) {
    return String.format("%d %d %d", stats[0], stats[1], stats[2]);
  }

  private static Set<ByteString> readAddressesFromFile(String filePath) {
    Set<ByteString> addresses = new HashSet<>();
    try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
      String line;
      while ((line = br.readLine()) != null) {
        byte[] address = Commons.decode58Check(line);
        if (address != null) {
          addresses.add(ByteString.copyFrom(address));
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return addresses;
  }

  private static boolean isFakeUSDT(ByteString contractAddress, Wallet wallet) throws Exception {
    String symbol = "";
    byte[] symbolBytes = triggerConstant(contractAddress, "95d89b41", wallet);
    if (symbolBytes != null && symbolBytes.length >= 3 * 32) {
      symbol = new String(symbolBytes, 2 * 32,
          new DataWord(Arrays.copyOfRange(symbolBytes, 32, 2 * 32)).intValue()).toUpperCase();
    }

    if (symbol.contains("USDT") || symbol.contains("USTD") || symbol.contains("UTSD")) {
      logger.info("Fake USDT: {} [{}]", StringUtil.encode58Check(contractAddress.toByteArray()), symbol);
      return true;
    }

    return false;
  }

  private static byte[] triggerConstant(ByteString contractAddress, String selector, Wallet wallet) throws Exception {
    SmartContractOuterClass.TriggerSmartContract contract =
        SmartContractOuterClass.TriggerSmartContract.newBuilder()
            .setContractAddress(contractAddress)
            .setData(ByteString.copyFrom(ByteArray.fromHexString(selector)))
            .build();
    TransactionCapsule trxCap = wallet.createTransactionCapsule(contract,
        Protocol.Transaction.Contract.ContractType.TriggerSmartContract);

    GrpcAPI.TransactionExtention.Builder trxExtBuilder = GrpcAPI.TransactionExtention.newBuilder();
    GrpcAPI.Return.Builder retBuilder = GrpcAPI.Return.newBuilder();

    try {
      Protocol.Transaction tx = wallet.triggerConstantContract(contract, trxCap, trxExtBuilder, retBuilder);

      if (tx.getRet(0).getRet().equals(Protocol.Transaction.Result.code.SUCESS)) {
        return trxExtBuilder.getConstantResultCount() > 0 ? trxExtBuilder.getConstantResult(0).toByteArray() : null;
      }
    } catch (Exception ignored) { }

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

