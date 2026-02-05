package org.tron.program;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.exit.ExitManager;
import org.tron.common.log.LogService;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.core.Constant;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DynamicPropertiesStore;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j(topic = "app")
public class FullNode {

  /**
   * Start the FullNode.
   */
  public static void main(String[] args) {
    ExitManager.initExceptionHandler();
    Args.setParam(args, Constant.TESTNET_CONF);
    CommonParameter parameter = Args.getInstance();

    LogService.load(parameter.getLogbackPath());

    if (parameter.isSolidityNode()) {
      SolidityNode.start();
      return;
    }
    if (parameter.isKeystoreFactory()) {
      KeystoreFactory.start();
      return;
    }
    logger.info("Full node running.");
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

    // Init stake2.0
    AccountStore accountStore = appT.getChainBaseManager().getAccountStore();
    DynamicPropertiesStore dynamicPropertiesStore = appT.getDbManager().getDynamicPropertiesStore();
    try {
      dynamicPropertiesStore.getTotalNetWeight2();
    } catch (Exception ignored) {
      dynamicPropertiesStore.saveTotalNetWeight2(0);
      dynamicPropertiesStore.saveTotalEnergyWeight2(0);
      System.out.println("Init stake2.0, start");
      final AtomicLong count = new AtomicLong(0);
      accountStore.forEach(e -> {
        long bandwidth = e.getValue().getFrozenV2BalanceForBandwidth()
                + e.getValue().getDelegatedFrozenV2BalanceForBandwidth();
        long energy = e.getValue().getFrozenV2BalanceForEnergy()
                + e.getValue().getDelegatedFrozenV2BalanceForEnergy();
        if (bandwidth > 0) {
          dynamicPropertiesStore.addTotalNetWeight2(bandwidth / 1_000_000);
        }
        if (energy > 0) {
          dynamicPropertiesStore.addTotalEnergyWeight2(energy / 1_000_000);
        }
        if (count.incrementAndGet() % 1_000_000 == 0) {
          System.out.println("Init stake2.0, processed " + count.get());
        }
      });
      System.out.println("Init stake2.0, end");
      System.out.println("Stake for bandwidth: " + dynamicPropertiesStore.getTotalNetWeight2());
      System.out.println("Stake for energy: " + dynamicPropertiesStore.getTotalEnergyWeight2());
    }

    appT.startup();
    appT.blockUntilShutdown();
  }
}
