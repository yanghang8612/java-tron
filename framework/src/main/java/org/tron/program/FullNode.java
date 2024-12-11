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
import java.util.HashSet;
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

    Set<ByteString> chargers = readAddressesFromFile("/data/chargers.txt");
    Set<ByteString> exchanges = readAddressesFromFile("/data/exchanges.txt");
    long startNum = 51500000;
//    long startNum = 61000000;
    long endNum = ChainBaseManager.getInstance().getHeadBlockNum();

    byte[] USDT = Hex.decode("41a614f803B6FD780986A42c78Ec9c7f77e6DeD13C");
    byte[] TOPIC = Hex.decode("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");

    long withdrawTx = 0;
    long withdrawFee = 0;
    long chargeTx = 0;
    long chargeFee = 0;
    long collectTx = 0;
    long collectFee = 0;
    String currentDate = "";

    Wallet wallet = context.getBean(Wallet.class);
    for (long i = startNum; i < endNum; i++) {
      Protocol.Block block = wallet.getBlockByNum(i);
      GrpcAPI.TransactionInfoList infoList = wallet.getTransactionInfoByBlockNum(i);

      String date = new SimpleDateFormat("yyyyMMdd").format(new Date(block.getBlockHeader().getRawData().getTimestamp()));
      if (!date.equals(currentDate)) {
        System.out.printf("%s %d %d %d %d %d %d\n",
            currentDate,
            withdrawTx, withdrawFee,
            chargeTx, chargeFee,
            collectTx, collectFee);

        currentDate = date;
        withdrawTx = 0;
        withdrawFee = 0;
        chargeTx = 0;
        chargeFee = 0;
        collectTx = 0;
        collectFee = 0;
      }

      for (int j = 0; j < block.getTransactionsCount(); j++) {
        Protocol.Transaction tx = block.getTransactions(j);
        Protocol.TransactionInfo info = infoList.getTransactionInfo(j);

        TransactionCapsule txCap = new TransactionCapsule(tx);
        ByteString from = ByteString.copyFrom(txCap.getOwnerAddress());
        if (exchanges.contains(from)) {
          withdrawTx += 1;
          withdrawFee += info.getFee();
        } else if (chargers.contains(from)) {
          collectTx += 1;
          collectFee += info.getFee();
        } else {
          ByteString to = ByteString.copyFrom(new byte[]{});
          Protocol.Transaction.Contract contract = tx.getRawData().getContract(0);
          switch (contract.getType()) {
            case TransferContract:
              BalanceContract.TransferContract tc = contract.getParameter()
                  .unpack(BalanceContract.TransferContract.class);
              if (tc.getAmount() >= 1_000_000L) {
                to = tc.getToAddress();
              }
              break;
            case TriggerSmartContract:
              if (info.getResult() == Protocol.TransactionInfo.code.SUCESS
                  && info.getLogCount() == 1
                  && FastByteComparisons.equalByte(info.getLog(0).getTopics(0).toByteArray(), TOPIC)) {
                byte[] toBytes = Arrays.copyOfRange(info.getLog(0).getTopics(2).toByteArray(), 11, 32);
                toBytes[0] = 0x41;
                BigInteger amount = new BigInteger(info.getLog(0).getData().toByteArray());
                if (amount.compareTo(BigInteger.valueOf(1_000_000L)) >= 0) {
                  to = ByteString.copyFrom(toBytes);
                }
              }
          }
          if (chargers.contains(to)) {
            chargeTx += 1;
            chargeFee += info.getFee();
          }
        }
      }
    }
  }

  public static Set<ByteString> readAddressesFromFile(String filePath) {
    Set<ByteString> addresses = new HashSet<>();
    try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
      String line;
      while ((line = br.readLine()) != null) {
        byte[] address = null;
        try {
          address = Commons.decode58Check(line);
        } catch (Exception ignores) {}
        if (address != null) {
          addresses.add(ByteString.copyFrom(address));
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    logger.info("Read {} addresses from file {}", addresses.size(), filePath);
    return addresses;
  }
}
