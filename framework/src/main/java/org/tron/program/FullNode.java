package org.tron.program;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.crypto.ECKey;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.Base58;
import org.tron.common.utils.ByteUtil;
import org.tron.common.utils.Commons;
import org.tron.common.utils.Sha256Hash;
import org.tron.consensus.ConsensusDelegate;
import org.tron.core.Constant;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.http.FullNodeHttpApiService;
import org.tron.core.services.interfaceOnPBFT.RpcApiServiceOnPBFT;
import org.tron.core.services.interfaceOnPBFT.http.PBFT.HttpApiOnPBFTService;
import org.tron.core.services.interfaceOnSolidity.RpcApiServiceOnSolidity;
import org.tron.core.services.interfaceOnSolidity.http.solidity.HttpApiOnSolidityService;
import org.tron.protos.Protocol;
import org.tron.protos.contract.AssetIssueContractOuterClass;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;

import static org.tron.common.utils.Commons.decodeFromBase58Check;

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
    saveNextMaintenanceTime(context);
    shutdown(appT);
    mockWitness(context);
    buildTxs(context);

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

    rpcApiService.blockUntilShutdown();
  }

  public static void shutdown(final Application app) {
    logger.info("********register application shutdown hook********");
    Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
  }

  private static void buildTxs(TronApplicationContext context) {
    String method = CommonParameter.getInstance().testMethod;
    String params = CommonParameter.getInstance().testParams;
    if ("#".equals(params)) params = "";

    // build transaction
//    TriggerSmartContract.Builder builder = TriggerSmartContract.newBuilder();
//    builder.setOwnerAddress(ByteString.copyFrom(decodeFromBase58Check("TQE8qF59U3d4pHmiKaixAHgbXx1xcUZioA")));
//    builder.setContractAddress(ByteString.copyFrom(decodeFromBase58Check(CommonParameter.getInstance().testContract)));
//    builder.setData(ByteString.copyFrom(Hex.decode(AbiUtil.parseMethod(method, params))));
//    builder.setCallValue(0);
//    TriggerSmartContract contract = builder.build();
    AssetIssueContract.Builder sbBuilder = AssetIssueContract.newBuilder();
    sbBuilder.setOwnerAddress(ByteString.copyFrom(decodeFromBase58Check("TQE8qF59U3d4pHmiKaixAHgbXx1xcUZioA")));
    sbBuilder.setName(ByteString.copyFrom("ShaBi".getBytes()));
    sbBuilder.setAbbr(ByteString.copyFrom("SB".getBytes()));
    sbBuilder.setTotalSupply(100_000_000);
    sbBuilder.setTrxNum(1);
    sbBuilder.setNum(1);
    sbBuilder.setPrecision(6);
    sbBuilder.setStartTime(System.currentTimeMillis() + 1000L * 30 * 60);
    sbBuilder.setStartTime(System.currentTimeMillis() + 1000L * 30 * 60 + 1000L * 365 * 24 * 60 * 60);
    sbBuilder.setVoteScore(0);
    sbBuilder.setDescription(ByteString.copyFrom("This is a ShaBi".getBytes()));
    sbBuilder.setUrl(ByteString.copyFrom("http://shabi.com".getBytes()));
    sbBuilder.setFreeAssetNetLimit(0);
    sbBuilder.setPublicFreeAssetNetLimit(0);
    AssetIssueContract contract = sbBuilder.build();
    TransactionCapsule trxCapWithoutFeeLimit = new TransactionCapsule(contract,
        Protocol.Transaction.Contract.ContractType.AssetIssueContract);
    Protocol.Transaction.Builder transactionBuilder = trxCapWithoutFeeLimit.getInstance().toBuilder();
    Protocol.Transaction.raw.Builder rawBuilder = trxCapWithoutFeeLimit.getInstance().getRawData()
        .toBuilder();
    rawBuilder.setFeeLimit(100_000_000L);
//    rawBuilder.setRefBlockHash(ByteString.copyFrom(ByteUtil.hexToBytes("19b59068c6058ff4")));
//    rawBuilder.setRefBlockBytes(ByteString.copyFrom(new byte[]{0x00, 0x00}));
//    rawBuilder.setRefBlockNum(0);
//    rawBuilder.setExpiration(60000);
    transactionBuilder.setRawData(rawBuilder);

    BigInteger priK = new BigInteger("fafe24df018b26124118504c478ed3682a7fcc089c3a12d86cfba1f4f3d0e3ae", 16);
    ECKey ecKey = ECKey.fromPrivate(priK);

    Manager manager = context.getBean(Manager.class);
    ExecutorService es = Executors.newFixedThreadPool(4);
    CountDownLatch countDown = new CountDownLatch(4);
    for (int i = 0; i < 4; i++) {
      int cnt;
      if (i != 3) {
        cnt = CommonParameter.getInstance().testCnt / 4;
      } else {
        cnt = CommonParameter.getInstance().testCnt - CommonParameter.getInstance().testCnt / 4 * 3;
      }
      es.submit(() -> {
        for (int j = 0; j < cnt; j++) {
          Protocol.Transaction trx = transactionBuilder.build();
          trx = setTimestamp(trx);
          trx = sign(trx, ecKey);

          manager.getPendingTransactions().add(new TransactionCapsule(trx));
        }
        countDown.countDown();
      });
    }
    try {
      countDown.await();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private static Protocol.Transaction setTimestamp(Protocol.Transaction transaction) {
    long currentTime = System.currentTimeMillis();//*1000000 + System.nanoTime()%1000000;
    Protocol.Transaction.Builder builder = transaction.toBuilder();
    org.tron.protos.Protocol.Transaction.raw.Builder rowBuilder = transaction.getRawData()
        .toBuilder();
    rowBuilder.setTimestamp(currentTime);
    builder.setRawData(rowBuilder.build());
    return builder.build();
  }

  private static Protocol.Transaction sign(Protocol.Transaction transaction, ECKey myKey) {
    Protocol.Transaction.Builder transactionBuilderSigned = transaction.toBuilder();

    byte[] hash = Sha256Hash.hash(CommonParameter
        .getInstance().isECKeyCryptoEngine(), transaction.getRawData().toByteArray());
    List<Protocol.Transaction.Contract> listContract = transaction.getRawData().getContractList();
    for (int i = 0; i < listContract.size(); i++) {
      ECKey.ECDSASignature signature = myKey.sign(hash);
      ByteString bsSign = ByteString.copyFrom(signature.toByteArray());
      transactionBuilderSigned.addSignature(
          bsSign);//Each contract may be signed with a different private key in the future.
    }

    transaction = transactionBuilderSigned.build();
    return transaction;
  }

  private static void mockWitness(TronApplicationContext context) {
    Manager manager = context.getBean(Manager.class);
    String[] localWitnesses = {"TQE8qF59U3d4pHmiKaixAHgbXx1xcUZioA"};
    logger.info("Try to mock witness");
    manager.getWitnessStore().getAllWitnesses().forEach(witnessCapsule -> {
      manager.getWitnessStore().delete(witnessCapsule.getAddress().toByteArray());
    });
    int idx = 0;
    for (String acc : localWitnesses) {
      byte[] address = decodeFromBase58Check(acc);
      AccountCapsule account = new AccountCapsule(ByteString.copyFrom(address),
          Protocol.AccountType.Normal);
      account.setBalance(1000000000000000000L);
      long voteCount = 5000_000 + idx * 10;
      account.addVotes(ByteString.copyFrom(address), voteCount);
      context.getBean(Manager.class).getAccountStore().put(address, account);
      ByteString byteStringaddress = ByteString.copyFrom(address);
      final AccountCapsule accountCapsule;
      if (!manager.getChainBaseManager().getAccountStore().has(address)) {
        accountCapsule = new AccountCapsule(ByteString.EMPTY, byteStringaddress, Protocol.AccountType.AssetIssue, 0L);
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
        .get(decodeFromBase58Check("TQE8qF59U3d4pHmiKaixAHgbXx1xcUZioA"));
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
