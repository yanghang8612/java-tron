package org.tron.program;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.beust.jcommander.JCommander;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import org.tron.common.utils.DecodeUtil;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.utils.FastByteComparisons;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.BlockStore;
import org.tron.core.net.P2pEventHandlerImpl;
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
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.Common;
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
  public static void main(String[] args) {
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

    long startNum = 45000000;
    long endNum = 65000000;

    Map<Long, long[]> stats = new HashMap<>();
    Map<Long, Set<String>> accounts = new HashMap<>();
    BigInteger precision = BigInteger.valueOf(1_000_000L);

    Wallet wallet = context.getBean(Wallet.class);
    String currentDate = "2022-10-12";
    byte[] USDT = Hex.decode("41a614f803B6FD780986A42c78Ec9c7f77e6DeD13C");
    byte[] TOPIC = Hex.decode("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    for (long i = startNum; i < endNum; i++) {
      if (i % 1000 == 0) {
        System.out.println("Processing block: " + i);
      }

      Protocol.Block block = wallet.getBlockByNum(i);
      GrpcAPI.TransactionInfoList infoList = wallet.getTransactionInfoByBlockNum(i);

      String date = new SimpleDateFormat("yyyy-MM-dd")
          .format(new Date(block.getBlockHeader().getRawData().getTimestamp()));

      if (!currentDate.equals(date)) {
        List<Map.Entry<Long, long[]>> sortedStats = new ArrayList<>(stats.entrySet());
        sortedStats.sort((e1, e2) -> Long.compare(e2.getValue()[0], e1.getValue()[0]));

        try {
          File outputFile = new File(currentDate.replace("-", "") + ".txt");
          try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            for (Map.Entry<Long, long[]> entry : sortedStats) {
              long key = entry.getKey();
              long[] values = entry.getValue();
              writer.write(String.format("%d %d %d %d %d %d%n",
                  key, values[0], accounts.get(key).size(), values[1], values[2], values[3]));
            }
          }
        } catch (IOException e) {
          logger.error("Failed to write stats to file for date " + currentDate, e);
        }

        currentDate = date;
        stats.clear();
        accounts.clear();
        System.out.println("Finished writing stats for date: " + currentDate);
      }

      for (int j = 0; j < block.getTransactionsCount(); j++) {
        Protocol.Transaction tx = block.getTransactions(j);
        Protocol.TransactionInfo info = infoList.getTransactionInfo(j);

        if (info.getResult() == Protocol.TransactionInfo.code.SUCESS
            && FastByteComparisons.equalByte(info.getContractAddress().toByteArray(), USDT)
            && info.getLogCount() == 1
            && FastByteComparisons.equalByte(info.getLog(0).getTopics(0).toByteArray(), TOPIC)) {
          BigInteger amount = new BigInteger(info.getLog(0).getData().toByteArray());
          long category = amount.divide(precision).longValue();
          if (!stats.containsKey(category)) {
            stats.put(category, new long[4]);
            accounts.put(category, new HashSet<>());
          }
          stats.get(category)[0] += 1;
          Protocol.ResourceReceipt receipt = info.getReceipt();
          stats.get(category)[1] += receipt.getEnergyUsageTotal() - receipt.getEnergyUsage() - receipt.getOriginEnergyUsage();
          stats.get(category)[2] += receipt.getEnergyUsage() + receipt.getOriginEnergyUsage();
          stats.get(category)[3] += receipt.getEnergyUsageTotal();

          TransactionCapsule txCap = new TransactionCapsule(tx);
          String owner = Hex.toHexString(txCap.getOwnerAddress());
          accounts.get(category).add(owner);
        }
      }
    }
  }
}
