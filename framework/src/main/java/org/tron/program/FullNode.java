package org.tron.program;

import com.beust.jcommander.JCommander;
import com.google.protobuf.ByteString;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.api.GrpcAPI;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.exit.ExitManager;
import org.tron.common.log.LogService;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.TransactionRetStore;
import org.tron.protos.Protocol;

@Slf4j(topic = "app")
public class FullNode {

  /**
   * Start the FullNode.
   */
  public static void main(String[] args) {
    ExitManager.initExceptionHandler();
    logger.info("Full node running.");
    Args.setParam(args, Constant.TESTNET_CONF);
    CommonParameter parameter = Args.getInstance();

    LogService.load(parameter.getLogbackPath());

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

    Wallet wallet = context.getBean(Wallet.class);
    DynamicPropertiesStore dps = context.getBean(DynamicPropertiesStore.class);
    Set<ByteString> suicides = new HashSet<>();

    ByteString suicide = ByteString.copyFrom("suicide".getBytes());
    ByteString create = ByteString.copyFrom("create".getBytes());
    long endBlockNum = dps.getLatestBlockHeaderNumber();
    for (int i = 0; i < endBlockNum; i++) {
      GrpcAPI.TransactionInfoList list = wallet.getTransactionInfoByBlockNum(i);
      Set<ByteString> created = new HashSet<>();
      Set<ByteString> deleted = new HashSet<>();
      for (Protocol.TransactionInfo info : list.getTransactionInfoList()) {
        for (Protocol.InternalTransaction it : info.getInternalTransactionsList()) {
          if (it.getNote().equals(suicide)) {
            deleted.add(it.getCallerAddress());
          } else if (it.getNote().equals(create)) {
            if (suicides.contains(it.getCallerAddress())) {
              System.out.println("Find recreate: " + Hex.toHexString(it.getCallerAddress().toByteArray()));
            }
            created.add(it.getCallerAddress());
          }
        }
      }

      for (ByteString address : deleted) {
        if (!created.contains(address)) {
          suicides.add(address);
        }
      }

      if (i % 100000 == 0) {
        System.out.println("Tracked block num: " + i);
      }
    }

//    appT.startup();
//    appT.blockUntilShutdown();
  }
}
