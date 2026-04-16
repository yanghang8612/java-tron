package org.tron.program;

import static org.tron.common.utils.Commons.decodeFromBase58Check; import java.util.ArrayList; import java.util.Comparator; import java.util.List; import org.tron.common.utils.Commons; import org.tron.consensus.ConsensusDelegate; import com.google.protobuf.ByteString; import org.tron.core.capsule.AccountCapsule; import org.tron.core.capsule.WitnessCapsule; import org.tron.core.db.Manager; import org.tron.protos.Protocol; import org.tron.protos.Protocol.AccountType;
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
    Application appT = ApplicationFactory.create(context);saveNextMaintenanceTime(context);
    context.registerShutdownHook();mockWitness(context);
    appT.startup();
    appT.blockUntilShutdown();
  }

  private static void mockWitness(TronApplicationContext context) {
    Manager manager = context.getBean(Manager.class);
    String[] localWitnesses = {"TXtrbmfwZ2LxtoCveEhZT86fTss1w8rwJE",
        "TWKKwLswTTcK5cp31F2bAteQrzU8cYhtU5", "TT4MHXVApKfbcq7cDLKnes9h9wLSD4eMJi",
        "TCw4yb4hS923FisfMsxAzQ85srXkK6RWGk", "TLYUrci5Qw5fUPho2GvFv38kAK4QSmdhhN",
        "TRx32uh7TQjdnLFKyWVPKJBfEn1XWjJtcm",
        "TRxF8fZERk4XzQZe1SzvkS5nyNJ7x6tGZ5",
        "TRxUztFKWdXy42MSdiHQoef5VLaXADMJp3",
        "TRxh1GnspMRadaU37UzrRRpkME2EkwCHg4",
        "TRxsiQ2vugWqY2JGr39NHqysAw5zHfWhpU",
        "TRx3MZDxWzTBW3HYX3ZWGEBrvAC8upGA8C",
        "TRxFANjAvztBibiqPRWgG841fVP12BCH7d",
        "TRxVs5MRUy2yHn2kqwev81VjYwXBdYdXrD",
        "TRxhePptGctYfCpxFCsLLAHUr1iShFkGC1",
        "TRxtaoGBJeSwQJu5551cBhaw5sW3vaazuF",
        "TRx3cWa892UxbCaoqCjidp3r946SLZ6U72",
        "TRxFZ7TDgQGF8MfLxnjQ9EqL5WtEiUmTmH",
        "TRxVyqGWNwiPCetP7EQTnukdsVGgMpXzwj",
        "TRxinhH2wZa4zPCqgcUgEZTx3uYs9bFKuM",
        "TRxtfixDf8e4MnZw6zRAVbL3isVnnaiq2o",
        "TRx4sTiyZuDN8whJUyovHZNTk6UYdsqqwg",
        "TRxFiLJp8i5YMQyG2rJFzNA9htaTc7wLcf",
        "TRxXnVabXh8QzdPvAGigmyuYuC391hzmwL",
        "TRxiyR3cJPwyMMpq3WQQF7xiRkNDLkyd9X",
        "TRxu36iquybaSti8ZhVzZ2tPgK7NiXTrSn",
        "TRx4znAxu5FWxb5ccVUX89TtZ8qWF2PM2b",
        "TRxYCcQNn7U7RtN7ZqF36GQYhfMTKnoarw"
    };

    AccountCapsule existAccount = manager.getAccountStore()
        .get(Commons.decodeFromBase58Check(localWitnesses[4]));
    if (existAccount != null && existAccount.getBalance() > 20000_000_000L) {
      logger.info("Not mock witness, not the first time to kill");
      return;
    }

    logger.info("Try to mock witness");

    manager.getWitnessStore().getAllWitnesses().forEach(witnessCapsule -> {
 //     manager.getWitnessStore().delete(witnessCapsule.getAddress().toByteArray());
    });

    int idx = 0;
    for (String acc : localWitnesses) {
      byte[] address = Commons.decodeFromBase58Check(acc);

      AccountCapsule account = new AccountCapsule(ByteString.copyFrom(address),
          Protocol.AccountType.Normal);
      account.setBalance(1000000000000000000L);

      long voteCount = 5_000_000_000L + idx * 10L;

      account.addVotes(ByteString.copyFrom(address), voteCount);
      context.getBean(Manager.class).getAccountStore().put(address, account);

      ByteString byteStringaddress = ByteString.copyFrom(address);
      final AccountCapsule accountCapsule;
      if (!manager.getChainBaseManager().getAccountStore().has(address)) {
        accountCapsule = new AccountCapsule(ByteString.EMPTY, byteStringaddress, AccountType.AssetIssue, 0L);
      } else {
        accountCapsule = manager.getChainBaseManager().getAccountStore().getUnchecked(address);
      }
      accountCapsule.setIsWitness(true);
      manager.getChainBaseManager().getAccountStore().put(address, accountCapsule);

      final WitnessCapsule witnessCapsule = new WitnessCapsule(byteStringaddress, voteCount,
          "mock_witness_" + idx);
      witnessCapsule.setIsJobs(true);
      manager.getChainBaseManager().getWitnessStore().put(address, witnessCapsule);
      ConsensusDelegate consensusDelegate = context.getBean(ConsensusDelegate.class);

      List<ByteString> witnesses = new ArrayList<>();
      consensusDelegate.getAllWitnesses().forEach(witnessCapsule1 -> {
        if (witnessCapsule1.getIsJobs()) {
          witnesses.add(witnessCapsule1.getAddress());
        }
      });
      witnesses.sort(Comparator.comparingLong((ByteString b) ->
          consensusDelegate.getWitness(b.toByteArray()).getVoteCount())
          .reversed()
          .thenComparing(Comparator.comparingInt(ByteString::hashCode).reversed()));
      consensusDelegate.saveActiveWitnesses(witnesses);
    }
  }

  private static void saveNextMaintenanceTime(TronApplicationContext context) {
    Manager manager = context.getBean(Manager.class);
    AccountCapsule existAccount = manager.getAccountStore()
        .get(decodeFromBase58Check("TXtrbmfwZ2LxtoCveEhZT86fTss1w8rwJE"));
    if (existAccount == null) {
      long start = 1547532000000L;
      int interval = 300000;
      long next = start;
      while (next < System.currentTimeMillis()) {
        next += interval;
      }
      manager.getDynamicPropertiesStore().saveMaintenanceTimeInterval(interval);
      manager.getDynamicPropertiesStore().saveNextMaintenanceTime(next);
    }
  }

}
