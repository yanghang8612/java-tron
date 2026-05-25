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

    // fast-sync-stats 功能1:首次升级时建立 total2 基线(幂等)
    org.tron.core.store.AccountStore accountStore = appT.getChainBaseManager().getAccountStore();
    org.tron.core.store.DynamicPropertiesStore dps = appT.getDbManager().getDynamicPropertiesStore();
    if (dps.getUnchecked("TOTAL_NET_WEIGHT2".getBytes()) == null) {
      // 累加到局部变量,扫描全部完成后再一次性落库:
      // 1) 崩溃安全/幂等——扫描中途退出时 TOTAL_NET_WEIGHT2 仍不存在,下次启动会重新扫描;
      // 2) TOTAL_NET_WEIGHT2 作为存在性标记最后写,即使两次写之间崩溃也会触发重扫;
      // 3) 避免原先每账户一次 read-modify-write 的海量 store 写入。
      long netWeight2 = 0;
      long energyWeight2 = 0;
      long count = 0;
      java.util.Iterator<java.util.Map.Entry<byte[], org.tron.core.capsule.AccountCapsule>> it =
          accountStore.iterator();
      try {
        while (it.hasNext()) {
          org.tron.core.capsule.AccountCapsule account = it.next().getValue();
          long bandwidth = account.getFrozenV2BalanceForBandwidth()
              + account.getDelegatedFrozenV2BalanceForBandwidth();
          long energy = account.getFrozenV2BalanceForEnergy()
              + account.getDelegatedFrozenV2BalanceForEnergy();
          if (bandwidth > 0) {
            netWeight2 += bandwidth / 1_000_000;
          }
          if (energy > 0) {
            energyWeight2 += energy / 1_000_000;
          }
          if (++count % 1_000_000 == 0) {
            logger.info("Init stake2.0 weight, processed {}", count);
          }
        }
        // 扫描成功完成后落库:energy 先写,net(存在性标记)最后写
        dps.saveTotalEnergyWeight2(energyWeight2);
        dps.saveTotalNetWeight2(netWeight2);
        logger.info("Init stake2.0 weight done, net={}, energy={}", netWeight2, energyWeight2);
      } finally {
        if (it instanceof java.io.Closeable) {
          try {
            ((java.io.Closeable) it).close();
          } catch (Exception e) {
            logger.error("Close account iterator.", e);
          }
        }
      }
    }

    appT.startup();
    appT.blockUntilShutdown();
  }
}
