package org.tron.program;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.org.apache.bcel.internal.generic.NEW;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.crypto.Hash;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.StringUtil;
import org.tron.core.Constant;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.http.FullNodeHttpApiService;
import org.tron.core.services.interfaceOnPBFT.RpcApiServiceOnPBFT;
import org.tron.core.services.interfaceOnPBFT.http.PBFT.HttpApiOnPBFTService;
import org.tron.core.services.interfaceOnSolidity.RpcApiServiceOnSolidity;
import org.tron.core.services.interfaceOnSolidity.http.solidity.HttpApiOnSolidityService;
import org.tron.core.store.StoreFactory;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass;

@Slf4j(topic = "app")
public class FullNode {
  
  public static final int dbVersion = 2;

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
      logger.info("Here is the help message.");
      return;
    }

    if (Args.getInstance().isDebug()) {
      logger.info("in debug mode, it won't check energy time");
    } else {
      logger.info("not in debug mode, it will check energy time");
    }

    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.setAllowCircularReferences(false);
    TronApplicationContext context =
        new TronApplicationContext(beanFactory);
    context.register(DefaultConfig.class);

    context.refresh();
    Application appT = ApplicationFactory.create(context);
    shutdown(appT);

    // grpc api server
    RpcApiService rpcApiService = context.getBean(RpcApiService.class);
    appT.addService(rpcApiService);

    // http api server
    FullNodeHttpApiService httpApiService = context.getBean(FullNodeHttpApiService.class);
    if (CommonParameter.getInstance().fullNodeHttpEnable) {
      appT.addService(httpApiService);
    }

    // full node and solidity node fuse together
    // provide solidity rpc and http server on the full node.
    if (Args.getInstance().getStorage().getDbVersion() == dbVersion) {
      RpcApiServiceOnSolidity rpcApiServiceOnSolidity = context
          .getBean(RpcApiServiceOnSolidity.class);
      appT.addService(rpcApiServiceOnSolidity);
      HttpApiOnSolidityService httpApiOnSolidityService = context
          .getBean(HttpApiOnSolidityService.class);
      if (CommonParameter.getInstance().solidityNodeHttpEnable) {
        appT.addService(httpApiOnSolidityService);
      }
    }

    // PBFT API (HTTP and GRPC)
    if (Args.getInstance().getStorage().getDbVersion() == dbVersion) {
      RpcApiServiceOnPBFT rpcApiServiceOnPBFT = context
          .getBean(RpcApiServiceOnPBFT.class);
      appT.addService(rpcApiServiceOnPBFT);
      HttpApiOnPBFTService httpApiOnPBFTService = context
          .getBean(HttpApiOnPBFTService.class);
      appT.addService(httpApiOnPBFTService);
    }

    appT.initServices(parameter);
    appT.startServices();
    appT.startup();

    Repository repository = RepositoryImpl.createRoot(StoreFactory.getInstance());
    long endBlockNum = repository.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    int days = CommonParameter.getInstance().day;
    int threads = CommonParameter.getInstance().thread;
    boolean findUSDT = CommonParameter.getInstance().usdt == 1;
    int blocks = 24 * 60 * 20;
    int[] slot = new int[threads];
    for (int i = 0, j = 0; i < days; i++, j = (j + 1) % threads) {
      slot[j] += 1;
    }
    List<Thread> threadList = new ArrayList<>();
    for (int i = threads - 1; i >= 0; i--) {
      long firstBlockNum = findFirstBlockNumOfADay(endBlockNum - (long) slot[i] * blocks, repository);
      threadList.add(new Thread(new TraversalTask(firstBlockNum, endBlockNum, findUSDT), "Traversal-" + i));
      endBlockNum = firstBlockNum - 1;
    }
    threadList.forEach(Thread::start);

    rpcApiService.blockUntilShutdown();
  }

  private static long findFirstBlockNumOfADay(long start, Repository rep) {
    LocalDateTime dateTime = Instant.ofEpochMilli(rep.getBlockByNum(start).getTimeStamp())
        .atZone(ZoneOffset.ofHours(8)).toLocalDateTime();
    int flag = dateTime.getHour() >= 12 ? 1 : -1, i = 1;
    int day = dateTime.getDayOfYear();
    while (true) {
      int curDay = Instant.ofEpochMilli(rep.getBlockByNum(start + flag * i).getTimeStamp())
          .atZone(ZoneOffset.ofHours(8)).toLocalDateTime().getDayOfYear();
      if (day != curDay) break;
      else i += 1;
    }
    return start + flag * i + (flag == -1 ? 1 : 0);
  }

  private static class Data {
    long outOfTime = 0;
    long txCnt = 0;

    @Override
    public String toString() {
      return outOfTime + " " + txCnt;
    }
  }

  private static class TraversalTask implements Runnable {

    private final long startBlockNum;

    private final long endBlockNum;

    private final boolean findUSDT;

    public TraversalTask(long startBlockNum, long endBlockNum, boolean findUSDT) {
      this.startBlockNum = startBlockNum;
      this.endBlockNum = endBlockNum;
      this.findUSDT = findUSDT;
      System.out.println(startBlockNum + ":" + endBlockNum);
    }

    @Override
    public void run() {
      Map<String, Data> map = new HashMap<>();
      Repository repository = RepositoryImpl.createRoot(StoreFactory.getInstance());
      byte[] selector = Hash.sha3("transfer(address,uint256)".getBytes());
      long txCnt = 0, outOfTime = 0, curTxCnt = 0, curOutOfTime = 0,
          startTime = System.currentTimeMillis(), curBlockNum = startBlockNum;
      for (int days = 0; curBlockNum <= endBlockNum; curBlockNum++) {
        BlockCapsule blockCapsule = repository.getBlockByNum(curBlockNum);
        LocalDateTime date = Instant.ofEpochMilli(blockCapsule.getTimeStamp())
            .atZone(ZoneOffset.ofHours(8)).toLocalDateTime();
        if (days == 0) days = date.getDayOfYear();
        if (date.getDayOfYear() != days) {
          txCnt += curTxCnt;
          outOfTime += curOutOfTime;
          System.out.println(Thread.currentThread().getName() + ": " + date.plusSeconds(6)
              + " " + curTxCnt + " " + curOutOfTime + " " + txCnt + " " + outOfTime
              + " " + (System.currentTimeMillis() - startTime) + "ms");
          try {
            for (String key : map.keySet()) {
              BufferedWriter bw = new BufferedWriter(new FileWriter(key, true));
              DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");
              bw.write(String.format("%s %d %d%n", df.format(date.plusDays(1)), map.get(key).outOfTime, map.get(key).txCnt));
              bw.flush();
              bw.close();
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
          curTxCnt = curOutOfTime = 0;
          startTime = System.currentTimeMillis();
          days = date.getDayOfYear();
          map = new HashMap<>();
        }
        String sr = StringUtil.encode58Check(blockCapsule.getWitnessAddress().toByteArray());
        List<TransactionCapsule> transactions = blockCapsule.getTransactions();
        for (TransactionCapsule cap : transactions) {
          Protocol.Transaction t = cap.getInstance();
          List<Protocol.Transaction.Contract> contracts = t.getRawData().getContractList();
          if (contracts.size() > 0 && contracts.get(0).getType()
              == Protocol.Transaction.Contract.ContractType.TriggerSmartContract) {
            SmartContractOuterClass.TriggerSmartContract contract;
            try {
              contract = contracts.get(0).getParameter().unpack(SmartContractOuterClass.TriggerSmartContract.class);
              if (!findUSDT || ("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t".equals(
                  StringUtil.encode58Check(contract.getContractAddress().toByteArray()))
                  && check(selector, contract.getData().toByteArray()))) {
                curTxCnt += 1;
                if (!map.containsKey(sr)) map.put(sr, new Data());
                map.get(sr).txCnt += 1;
                if (cap.getContractResult() == Protocol.Transaction.Result.contractResult.OUT_OF_TIME) {
                  curOutOfTime += 1;
                  map.get(sr).outOfTime += 1;
                  BufferedWriter bw = new BufferedWriter(new FileWriter("contract", true));
                  bw.write(String.format("%s %s%n",
                      StringUtil.encode58Check(contract.getContractAddress().toByteArray()),
                      cap.getTransactionId().toString()));
                  bw.flush();
                  bw.close();
                }
              }
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        }
      }
    }
  }

  private static boolean check(byte[] a, byte[] b) {
    if (b == null || b.length < 4) return false;
    for (int i = 0; i < 4; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  public static void shutdown(final Application app) {
    logger.info("********register application shutdown hook********");
    Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
  }
}
