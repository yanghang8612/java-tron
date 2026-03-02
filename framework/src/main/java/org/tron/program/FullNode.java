package org.tron.program;

import static org.tron.protos.Protocol.Transaction.Contract.ContractType.AccountPermissionUpdateContract;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.exit.ExitManager;
import org.tron.common.log.LogService;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.BlockStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AccountContract;

@Slf4j(topic = "app")
public class FullNode {

  /**
   * Start the FullNode.
   */
  public static void main(String[] args) throws InvalidProtocolBufferException {
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

    DynamicPropertiesStore dps = context.getBean(DynamicPropertiesStore.class);
    Wallet wallet = context.getBean(Wallet.class);

    long startNum = 60000000;
    long endNum = dps.getLatestBlockHeaderNumber();

    ByteString userA = ByteString.copyFrom(Hex.decode("416f3dbF0c26C768BFe6CDd45c6C2Aef572A3B8d68"));
    ByteString userB = ByteString.copyFrom(Hex.decode("4168624F542Fd16682160DC929D972D1A69D2353CF"));
    ByteString userC = ByteString.copyFrom(Hex.decode("4150f2923462023dEE48c499C5f71c15D0a5Ae31B3"));
    Set<ByteString> userSet = new HashSet<>();
    userSet.add(userA);
    userSet.add(userB);
    userSet.add(userC);

    for (long i = startNum; i < endNum; i++) {
      Protocol.Block block = wallet.getBlockByNum(i);
      if (block == null) {
        logger.warn("block {} is null", i);
        continue;
      }

      for (Protocol.Transaction tx : block.getTransactionsList()) {
        if (tx.getRawData().getContract(0).getType() == AccountPermissionUpdateContract) {
          AccountContract.AccountPermissionUpdateContract contract = tx.getRawData().getContract(0).getParameter()
              .unpack(AccountContract.AccountPermissionUpdateContract.class);
          if (contain(contract.getOwner(), userSet)) {
            logger.info("block num: {}, tx id: {}", i, new TransactionCapsule(tx).getTransactionId().toString());
            continue;
          }

          for (Protocol.Permission permission : contract.getActivesList()) {
            if (contain(permission, userSet)) {
              logger.info("block num: {}, tx id: {}", i, new TransactionCapsule(tx).getTransactionId().toString());
              break;
            }
          }
        }
      }

      if (i % 100000 == 0) {
        logger.info("processed block num: {}", i);
      }
    }

    System.exit(0);

    Application appT = ApplicationFactory.create(context);
    context.registerShutdownHook();
    appT.startup();
    appT.blockUntilShutdown();
  }

  private static boolean contain(Protocol.Permission permission, Set<ByteString> userSet) {
    for (Protocol.Key key : permission.getKeysList()) {
      if (userSet.contains(key.getAddress())) {
        return true;
      }
    }
    return false;
  }
}

