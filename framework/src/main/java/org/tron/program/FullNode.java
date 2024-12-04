package org.tron.program;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.beust.jcommander.JCommander;
import java.io.File;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.checkerframework.checker.units.qual.C;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.api.GrpcAPI;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
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
  public static void main(String[] args) throws InvalidProtocolBufferException {
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

    long startNum = 67520000;
//    long startNum = 61000000;
    long endNum = ChainBaseManager.getInstance().getHeadBlockNum();
    Wallet wallet = context.getBean(Wallet.class);
    long level1 = 0;
    long level2 = 0;
    long level3 = 0;
    long level4 = 0;
    long level5 = 0;
    Date currentDate = null;
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    Set<String> ba = new HashSet<>();
    ba.add("TJDENsfBJs4RFETt1X1W8wMDc8M5XnJhCe");
    ba.add("TJCo98saj6WND61g1uuKwJ9GMWMT9WkJFo");
    ba.add("TV6MuMXfmLbBqPZvBHdwFsDnQeVfnmiuSi");
    ba.add("TCYDDPYUiq97JU1RwBMGdf7jWTUTZ8GmgT");
    ba.add("TQrY8tryqsYVCYS3MFbtffiPp2ccyn4STm");
    ba.add("TYASr5UV6HEcXatwdFQfmLVUqQQQMUxHLS");
    ba.add("TDqSquXBgUCLYvYC4XZgrprLK589dkhSCf");
    ba.add("TNXoiAJ3dct8Fjg4M9fkLFh9S2v9TXc32G");
    ba.add("TCLgK89AnXbC9rewvhNb9UgXCc2qJJpBXh");
    ba.add("TK4ykR48cQQoyFcZ5N4xZCbsBaHcg6n3gJ");
    ba.add("TAzsQ9Gx8eqFNFSKbeXrbi45CuVPHzA8wr");
    ba.add("TJqwA7SoZnERE4zW5uDEiPkbz4B66h9TFj");
    ba.add("TAUN6FwrnwwmaEqYcckffC7wYmbaS6cBiX");
    ba.add("TJ5usJLLwjwn7Pw3TPbdzreG7dvgKzfQ5y");
    for (long i = startNum; i < endNum; i++) {
      Protocol.Block block = wallet.getBlockByNum(i);

      Date date = new Date(block.getBlockHeader().getRawData().getTimestamp());
      Calendar dateCal = Calendar.getInstance(Locale.CHINA);
      dateCal.setTime(date);
      if (dateCal.get(Calendar.MINUTE) % 5 == 0 && (currentDate == null || date.getTime() - currentDate.getTime() > 4 * 60 * 1000)) {
        System.out.printf("%s %d %d %d %d %d\n", sdf.format(date), level1, level2, level3, level4, level5);
        currentDate = date;
        level1 = level2 = level3 = level4 = level5 = 0;
      }

      for (int j = 0; j < block.getTransactionsCount(); j++) {
        Protocol.Transaction tx = block.getTransactions(j);
        TransactionCapsule txCap = new TransactionCapsule(tx);

        String owner = StringUtil.encode58Check(txCap.getOwnerAddress());
        if (!ba.contains(owner) || tx.getRawData().getTimestamp() == 0) {
          continue;
        }

        long delay = block.getBlockHeader().getRawData().getTimestamp() - tx.getRawData().getTimestamp();
        if (delay < 3000) {
          level1 += 1;
        } else if (delay < 10_000) {
          level2 += 1;
        } else if (delay < 30_000) {
          level3 += 1;
        } else if (delay < 60_000) {
          level4 += 1;
        } else {
          level5 += 1;
        }
      }
    }
  }
}