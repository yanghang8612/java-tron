package org.tron.program;

import com.beust.jcommander.JCommander;
import java.text.SimpleDateFormat;
import lombok.extern.slf4j.Slf4j;
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
import org.tron.core.capsule.TransactionRetCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
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
//    appT.startup();

    Wallet wallet = context.getBean(Wallet.class);
    long start = 72520562L;
    long end = wallet.getBlockByLatestNum(1).getBlock(0).getBlockHeader().getRawData().getNumber();
    long totalFee = 0L;
    String curDate = "2025-05-26";
    for (long i = start; i < end; i++) {
      GrpcAPI.TransactionInfoList ret = wallet.getTransactionInfoByBlockNum(i);
      if (ret.getTransactionInfoCount() > 0) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(ret.getTransactionInfo(0).getBlockTimeStamp());
        if (!date.equals(curDate)) {
          System.out.println("Daily fee for " + curDate + " is: " + totalFee);
          curDate = date;
          totalFee = 0L;
        }
        for (Protocol.TransactionInfo info : ret.getTransactionInfoList()) {
          totalFee += info.getFee();
        }
      }

      if (i % 1000 == 0) {
        System.out.println("Processed block number: " + i);
      }
    }
    appT.blockUntilShutdown();
  }
}
