package org.tron.program;

import com.beust.jcommander.JCommander;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.api.GrpcAPI;
import org.tron.api.GrpcAPI.TransactionInfoList;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.exit.ExitManager;
import org.tron.common.log.LogService;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.FastByteComparisons;
import org.tron.common.utils.StringUtil;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DynamicPropertiesStore;

import java.util.concurrent.atomic.AtomicLong;
import org.tron.core.store.TransactionRetStore;
import org.tron.protos.Protocol;

@Slf4j(topic = "app")
public class FullNode {

  /**
   * Start the FullNode.
   */
  @SneakyThrows
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

    // Traverse internal USDT
    long number = 73115000L;
    Wallet wallet = context.getBean(Wallet.class);
    ByteString USDT = ByteString.copyFrom(Hex.decode("41a614f803B6FD780986A42c78Ec9c7f77e6DeD13C"));
    ByteString CALL = ByteString.copyFrom(Hex.decode("63616c6c"));
    FileWriter writer = new FileWriter("/data/usdt.txt", false);

    boolean stop = false;
    while (!stop) {
      TransactionInfoList infoList = wallet.getTransactionInfoByBlockNum(number);
      for (int i = 0; i < infoList.getTransactionInfoCount(); i++) {
        Protocol.TransactionInfo info = infoList.getTransactionInfo(i);
        String date = new SimpleDateFormat("yyyyMMdd").format(info.getBlockTimeStamp());
        if (date.equals("20250709")) {
          writer.close();
          stop = true;
          break;
        }

        for (Protocol.InternalTransaction interTx : info.getInternalTransactionsList()) {
          byte[] dataBytes = interTx.getData().toByteArray();
          String dataString = Hex.toHexString(dataBytes);
          if (!interTx.getRejected() && interTx.getNote().equals(CALL)
              && interTx.getTransferToAddress().equals(USDT)
              && (dataString.startsWith("a9059cbb") || dataString.startsWith("23b872dd"))) {
            double percent = (double) interTx.getEnergyUsed() / info.getReceipt().getEnergyUsageTotal();
            long fee = (long) (info.getFee() * percent);
            long energyTotal = interTx.getEnergyUsed();
            long energyUsage = (long) (info.getReceipt().getEnergyUsage() * percent);
            long originUsage = (long) (info.getReceipt().getOriginEnergyUsage() * percent);
            if (dataString.startsWith("a9059cbb") && dataBytes.length >= 68) {
              String from = StringUtil.encode58Check(interTx.getCallerAddress().toByteArray());
              dataBytes[4+11] = 0x41;
              String to = StringUtil.encode58Check(Arrays.copyOfRange(dataBytes, 15, 36));
              String amount = new BigInteger(Arrays.copyOfRange(dataBytes, 36, 68)).toString();

              writer.write(String.format("%s %d %d %s %s %s %d %d %d %d\n",
                  date, info.getBlockNumber(), i, from, to, amount, fee, energyTotal, energyUsage, originUsage));
            }

            if (dataString.startsWith("23b872dd") && dataBytes.length >= 100) {
              dataBytes[15] = 0x41;
              String from = StringUtil.encode58Check(Arrays.copyOfRange(dataBytes, 15, 36));
              dataBytes[36+11] = 0x41;
              String to = StringUtil.encode58Check(Arrays.copyOfRange(dataBytes, 47, 68));
              String amount = new BigInteger(Arrays.copyOfRange(dataBytes, 68, 100)).toString();

              writer.write(String.format("%s %d %d %s %s %s %d %d %d %d\n",
                  date, info.getBlockNumber(), i, from, to, amount, fee, energyTotal, energyUsage, originUsage));
            }
          }
        }
      }

      number += 1;
      if (number % 1000 == 0) {
        System.out.println("Processed block number: " + number);
      }
    }

    appT.startup();
    appT.blockUntilShutdown();
  }
}
