package org.tron.core.db;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.prometheus.client.Histogram;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.bouncycastle.util.encoders.Hex;
import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.api.GrpcAPI.TransactionInfoList;
import org.tron.common.args.GenesisBlock;
import org.tron.common.bloom.Bloom;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.FilterQuery;
import org.tron.common.logsfilter.capsule.*;
import org.tron.common.logsfilter.trigger.ContractEventTrigger;
import org.tron.common.logsfilter.trigger.ContractLogTrigger;
import org.tron.common.logsfilter.trigger.ContractTrigger;
import org.tron.common.logsfilter.trigger.Trigger;
import org.tron.common.overlay.message.Message;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.MetricLabels;
import org.tron.common.prometheus.Metrics;
import org.tron.common.runtime.RuntimeImpl;
import org.tron.common.utils.*;
import org.tron.common.zksnark.MerkleContainer;
import org.tron.consensus.Consensus;
import org.tron.consensus.base.Param.Miner;
import org.tron.core.ChainBaseManager;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.actuator.ActuatorCreator;
import org.tron.core.capsule.*;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.capsule.utils.TransactionUtil;
import org.tron.core.config.Parameter.ChainConstant;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ProposalController;
import org.tron.core.db.KhaosDatabase.KhaosBlock;
import org.tron.core.db.accountstate.TrieService;
import org.tron.core.db.accountstate.callback.AccountStateCallBack;
import org.tron.core.db.api.AssetUpdateHelper;
import org.tron.core.db.api.BandwidthPriceHistoryLoader;
import org.tron.core.db.api.EnergyPriceHistoryLoader;
import org.tron.core.db.api.MoveAbiHelper;
import org.tron.core.db2.ISession;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.db2.core.Chainbase;
import org.tron.core.db2.core.ITronChainBase;
import org.tron.core.db2.core.SnapshotManager;
import org.tron.core.exception.*;
import org.tron.core.metrics.MetricsKey;
import org.tron.core.metrics.MetricsUtil;
import org.tron.core.service.MortgageService;
import org.tron.core.store.*;
import org.tron.core.utils.TransactionRegister;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Permission;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.BalanceContract;
import org.tron.protos.contract.SmartContractOuterClass;

import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.tron.common.utils.Commons.adjustBalance;
import static org.tron.core.exception.BadBlockException.TypeEnum.CALC_MERKLE_ROOT_FAILED;
import static org.tron.protos.Protocol.Transaction.Contract.ContractType.TransferContract;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.OUT_OF_ENERGY;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;


@Slf4j(topic = "DB")
@Component
public class Manager {

  private static final int SHIELDED_TRANS_IN_BLOCK_COUNTS = 1;
  private static final String SAVE_BLOCK = "Save block: {}";
  private static final int SLEEP_TIME_OUT = 50;
  private static final int TX_ID_CACHE_SIZE = 100_000;
  private static final int SLEEP_FOR_WAIT_LOCK = 10;
  private static final int NO_BLOCK_WAITING_LOCK = 0;
  private final int shieldedTransInPendingMaxCounts =
      Args.getInstance().getShieldedTransInPendingMaxCounts();
  @Getter
  @Setter
  public boolean eventPluginLoaded = false;
  private int maxTransactionPendingSize = Args.getInstance().getMaxTransactionPendingSize();
  @Autowired(required = false)
  @Getter
  private TransactionCache transactionCache;
  @Autowired
  private KhaosDatabase khaosDb;
  @Getter
  @Autowired
  private RevokingDatabase revokingStore;
  @Getter
  private SessionOptional session = SessionOptional.instance();
  @Getter
  @Setter
  private boolean isSyncMode;
  @Getter
  private Object forkLock = new Object();
  // map<Long, IncrementalMerkleTree>
  @Getter
  @Setter
  private String netType;
  @Getter
  @Setter
  private ProposalController proposalController;
  @Getter
  @Setter
  private MerkleContainer merkleContainer;
  private ExecutorService validateSignService;
  private boolean isRunRePushThread = true;
  private boolean isRunTriggerCapsuleProcessThread = true;
  private BlockingQueue<TransactionCapsule> pushTransactionQueue = new LinkedBlockingQueue<>();
  @Getter
  private Cache<Sha256Hash, Boolean> transactionIdCache = CacheBuilder
      .newBuilder().maximumSize(TX_ID_CACHE_SIZE)
      .expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();
  @Autowired
  private AccountStateCallBack accountStateCallBack;
  @Autowired
  private TrieService trieService;
  private Set<String> ownerAddressSet = new HashSet<>();
  @Getter
  @Autowired
  private MortgageService mortgageService;
  @Autowired
  private Consensus consensus;
  @Autowired
  @Getter
  private ChainBaseManager chainBaseManager;
  // transactions cache
  private BlockingQueue<TransactionCapsule> pendingTransactions;
  @Getter
  private AtomicInteger shieldedTransInPendingCounts = new AtomicInteger(0);
  // transactions popped
  private List<TransactionCapsule> poppedTransactions =
      Collections.synchronizedList(Lists.newArrayList());
  // the capacity is equal to Integer.MAX_VALUE default
  private BlockingQueue<TransactionCapsule> rePushTransactions;
  private BlockingQueue<TriggerCapsule> triggerCapsuleQueue;
  // log filter
  private boolean isRunFilterProcessThread = true;
  private BlockingQueue<FilterTriggerCapsule> filterCapsuleQueue;

  @Getter
  private volatile long latestSolidityNumShutDown;
  @Getter
  private long lastUsedSolidityNum = -1;
  @Getter
  private int maxFlushCount;

  @Getter
  private final ThreadLocal<Histogram.Timer> blockedTimer = new ThreadLocal<>();

  private AtomicInteger blockWaitLock = new AtomicInteger(0);
  private Object transactionLock = new Object();

  /**
   * Cycle thread to rePush Transactions
   */
  private Runnable rePushLoop =
      () -> {
        while (isRunRePushThread) {
          TransactionCapsule tx = null;
          try {
            tx = getRePushTransactions().peek();
            if (tx != null) {
              this.rePush(tx);
            } else {
              TimeUnit.MILLISECONDS.sleep(SLEEP_TIME_OUT);
            }
          } catch (Throwable ex) {
            if (ex instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
            logger.error("Unknown exception happened in rePush loop.", ex);
            if (tx != null) {
              Metrics.counterInc(MetricKeys.Counter.TXS, 1,
                  MetricLabels.Counter.TXS_FAIL, MetricLabels.Counter.TXS_FAIL_ERROR);
            }
          } finally {
            if (tx != null && getRePushTransactions().remove(tx)) {
              Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, -1,
                  MetricLabels.Gauge.QUEUE_REPUSH);
            }
          }
        }
      };
  private Runnable triggerCapsuleProcessLoop =
      () -> {
        while (isRunTriggerCapsuleProcessThread) {
          try {
            TriggerCapsule triggerCapsule = triggerCapsuleQueue.poll(1, TimeUnit.SECONDS);
            if (triggerCapsule != null) {
              triggerCapsule.processTrigger();
            }
          } catch (InterruptedException ex) {
            logger.info(ex.getMessage());
            Thread.currentThread().interrupt();
          } catch (Throwable throwable) {
            logger.error("Unknown throwable happened in process capsule loop.", throwable);
          }
        }
      };

  private Runnable filterProcessLoop =
      () -> {
        while (isRunFilterProcessThread) {
          try {
            FilterTriggerCapsule filterCapsule = filterCapsuleQueue.poll(1, TimeUnit.SECONDS);
            if (filterCapsule != null) {
              filterCapsule.processFilterTrigger();
            }
          } catch (InterruptedException e) {
            logger.error("FilterProcessLoop get InterruptedException, error is {}.",
                    e.getMessage());
            Thread.currentThread().interrupt();
          } catch (Throwable throwable) {
            logger.error("Unknown throwable happened in filterProcessLoop. ", throwable);
          }
        }
      };

  private Comparator downComparator = (Comparator<TransactionCapsule>) (o1, o2) -> Long
      .compare(o2.getOrder(), o1.getOrder());

  public WitnessStore getWitnessStore() {
    return chainBaseManager.getWitnessStore();
  }

  public boolean needToUpdateAsset() {
    return getDynamicPropertiesStore().getTokenUpdateDone() == 0L;
  }

  public boolean needToMoveAbi() {
    return getDynamicPropertiesStore().getAbiMoveDone() == 0L;
  }

  private boolean needToLoadEnergyPriceHistory() {
    return getDynamicPropertiesStore().getEnergyPriceHistoryDone() == 0L;
  }

  private boolean needToLoadBandwidthPriceHistory() {
    return getDynamicPropertiesStore().getBandwidthPriceHistoryDone() == 0L;
  }

  public boolean needToSetBlackholePermission() {
    return getDynamicPropertiesStore().getSetBlackholeAccountPermission() == 0L;
  }

  private void resetBlackholeAccountPermission() {
    AccountCapsule blackholeAccount = getAccountStore().getBlackhole();

    byte[] zeroAddress = new byte[21];
    zeroAddress[0] = Wallet.getAddressPreFixByte();
    Permission owner = AccountCapsule
        .createDefaultOwnerPermission(ByteString.copyFrom(zeroAddress));
    blackholeAccount.updatePermissions(owner, null, null);
    getAccountStore().put(blackholeAccount.getAddress().toByteArray(), blackholeAccount);

    getDynamicPropertiesStore().saveSetBlackholePermission(1);
  }

  public DynamicPropertiesStore getDynamicPropertiesStore() {
    return chainBaseManager.getDynamicPropertiesStore();
  }

  public DelegationStore getDelegationStore() {
    return chainBaseManager.getDelegationStore();
  }

  public IncrementalMerkleTreeStore getMerkleTreeStore() {
    return chainBaseManager.getMerkleTreeStore();
  }

  public WitnessScheduleStore getWitnessScheduleStore() {
    return chainBaseManager.getWitnessScheduleStore();
  }

  public DelegatedResourceStore getDelegatedResourceStore() {
    return chainBaseManager.getDelegatedResourceStore();
  }

  public DelegatedResourceAccountIndexStore getDelegatedResourceAccountIndexStore() {
    return chainBaseManager.getDelegatedResourceAccountIndexStore();
  }

  public CodeStore getCodeStore() {
    return chainBaseManager.getCodeStore();
  }

  public ContractStore getContractStore() {
    return chainBaseManager.getContractStore();
  }

  public VotesStore getVotesStore() {
    return chainBaseManager.getVotesStore();
  }

  public ProposalStore getProposalStore() {
    return chainBaseManager.getProposalStore();
  }

  public ExchangeStore getExchangeStore() {
    return chainBaseManager.getExchangeStore();
  }

  public ExchangeV2Store getExchangeV2Store() {
    return chainBaseManager.getExchangeV2Store();
  }

  public StorageRowStore getStorageRowStore() {
    return chainBaseManager.getStorageRowStore();
  }

  public BlockIndexStore getBlockIndexStore() {
    return chainBaseManager.getBlockIndexStore();
  }

  public BlockingQueue<TransactionCapsule> getPendingTransactions() {
    return this.pendingTransactions;
  }

  public List<TransactionCapsule> getPoppedTransactions() {
    return this.poppedTransactions;
  }

  public BlockingQueue<TransactionCapsule> getRePushTransactions() {
    return rePushTransactions;
  }

  public void stopRePushThread() {
    isRunRePushThread = false;
  }

  public void stopRePushTriggerThread() {
    isRunTriggerCapsuleProcessThread = false;
  }

  public void stopFilterProcessThread() {
    isRunFilterProcessThread = false;
  }

  @PostConstruct
  public void init() {
    ChainBaseManager.init(chainBaseManager);
    Message.setDynamicPropertiesStore(this.getDynamicPropertiesStore());
    mortgageService
        .initStore(chainBaseManager.getWitnessStore(), chainBaseManager.getDelegationStore(),
            chainBaseManager.getDynamicPropertiesStore(), chainBaseManager.getAccountStore());
    accountStateCallBack.setChainBaseManager(chainBaseManager);
    trieService.setChainBaseManager(chainBaseManager);
    revokingStore.disable();
    revokingStore.check();
    transactionCache.initCache();
    this.setProposalController(ProposalController.createInstance(this));
    this.setMerkleContainer(
        merkleContainer.createInstance(chainBaseManager.getMerkleTreeStore(),
            chainBaseManager.getMerkleTreeIndexStore()));
    if (Args.getInstance().isOpenTransactionSort()) {
      this.pendingTransactions = new PriorityBlockingQueue(2000, downComparator);
      this.rePushTransactions = new PriorityBlockingQueue<>(2000, downComparator);
    } else {
      this.pendingTransactions = new LinkedBlockingQueue<>();
      this.rePushTransactions = new LinkedBlockingQueue<>();
    }
    this.triggerCapsuleQueue = new LinkedBlockingQueue<>();
    this.filterCapsuleQueue = new LinkedBlockingQueue<>();
    chainBaseManager.setMerkleContainer(getMerkleContainer());
    chainBaseManager.setMortgageService(mortgageService);
    this.initGenesis();
    try {
      this.khaosDb.start(chainBaseManager.getBlockById(
          getDynamicPropertiesStore().getLatestBlockHeaderHash()));
    } catch (ItemNotFoundException e) {
      logger.error(
          "Can not find Dynamic highest block from DB! \nnumber={} \nhash={}",
          getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
          getDynamicPropertiesStore().getLatestBlockHeaderHash());
      logger.error(
          "Please delete database directory({}) and restart",
          Args.getInstance().getOutputDirectory());
      System.exit(1);
    } catch (BadItemException e) {
      logger.error("DB data broken {}.", e.getMessage());
      logger.error(
          "Please delete database directory({}) and restart.",
          Args.getInstance().getOutputDirectory());
      System.exit(1);
    }
    getChainBaseManager().getForkController().init(this.chainBaseManager);

    if (Args.getInstance().isNeedToUpdateAsset() && needToUpdateAsset()) {
      new AssetUpdateHelper(chainBaseManager).doWork();
    }

    if (needToMoveAbi()) {
      new MoveAbiHelper(chainBaseManager).doWork();
    }

    if (needToLoadEnergyPriceHistory()) {
      new EnergyPriceHistoryLoader(chainBaseManager).doWork();
    }

    if (needToLoadBandwidthPriceHistory()) {
      new BandwidthPriceHistoryLoader(chainBaseManager).doWork();
    }

    if (needToSetBlackholePermission()) {
      resetBlackholeAccountPermission();
    }

    //for test only
    chainBaseManager.getDynamicPropertiesStore().updateDynamicStoreByConfig();

    // init liteFullNode
    // initLiteNode();

    long headNum = chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    logger.info("Current headNum is: {}.", headNum);
    boolean isLite = chainBaseManager.isLiteNode();
    logger.info("Node type is: {}.", isLite ? "lite" : "full");
    if (isLite) {
      logger.info("Lite node lowestNum: {}", chainBaseManager.getLowestBlockNum());
    }
    revokingStore.enable();
    validateSignService = Executors
        .newFixedThreadPool(Args.getInstance().getValidateSignThreadNum());
    Thread rePushThread = new Thread(rePushLoop);
    rePushThread.setDaemon(true);
    rePushThread.start();
    // add contract event listener for subscribing
    if (Args.getInstance().isEventSubscribe()) {
      startEventSubscribing();
      Thread triggerCapsuleProcessThread = new Thread(triggerCapsuleProcessLoop);
      triggerCapsuleProcessThread.setDaemon(true);
      triggerCapsuleProcessThread.start();
    }

    // start json rpc filter process
    if (CommonParameter.getInstance().isJsonRpcFilterEnabled()) {
      Thread filterProcessThread = new Thread(filterProcessLoop);
      filterProcessThread.start();
    }

    //initStoreFactory
    prepareStoreFactory();
    //initActuatorCreator
    ActuatorCreator.init();
    TransactionRegister.registerActuator();
    // init auto-stop
    try {
      initAutoStop();
    } catch (IllegalArgumentException e) {
      logger.error("Auto-stop params error: {}", e.getMessage());
      System.exit(1);
    }

    maxFlushCount = CommonParameter.getInstance().getStorage().getMaxFlushCount();
  }

  /**
   * init genesis block.
   */
  public void initGenesis() {
    chainBaseManager.initGenesis();
    BlockCapsule genesisBlock = chainBaseManager.getGenesisBlock();

    if (chainBaseManager.containBlock(genesisBlock.getBlockId())) {
      Args.getInstance().setChainId(genesisBlock.getBlockId().toString());
    } else {
      if (chainBaseManager.hasBlocks()) {
        logger.error(
            "Genesis block modify, please delete database directory({}) and restart.",
            Args.getInstance().getOutputDirectory());
        System.exit(1);
      } else {
        logger.info("Create genesis block.");
        Args.getInstance().setChainId(genesisBlock.getBlockId().toString());

        chainBaseManager.getBlockStore().put(genesisBlock.getBlockId().getBytes(), genesisBlock);
        chainBaseManager.getBlockIndexStore().put(genesisBlock.getBlockId());

        logger.info(SAVE_BLOCK, genesisBlock);
        // init Dynamic Properties Store
        chainBaseManager.getDynamicPropertiesStore().saveLatestBlockHeaderNumber(0);
        chainBaseManager.getDynamicPropertiesStore().saveLatestBlockHeaderHash(
            genesisBlock.getBlockId().getByteString());
        chainBaseManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(
            genesisBlock.getTimeStamp());
        this.initAccount();
        this.initWitness();
        this.khaosDb.start(genesisBlock);
        this.updateRecentBlock(genesisBlock);
        initAccountHistoryBalance();
      }
    }
  }

  /**
   * save account into database.
   */
  public void initAccount() {
    final CommonParameter parameter = CommonParameter.getInstance();
    final GenesisBlock genesisBlockArg = parameter.getGenesisBlock();
    genesisBlockArg
        .getAssets()
        .forEach(
            account -> {
              account.setAccountType("Normal"); // to be set in conf
              final AccountCapsule accountCapsule =
                  new AccountCapsule(
                      account.getAccountName(),
                      ByteString.copyFrom(account.getAddress()),
                      account.getAccountType(),
                      account.getBalance());
              chainBaseManager.getAccountStore().put(account.getAddress(), accountCapsule);
              chainBaseManager.getAccountIdIndexStore().put(accountCapsule);
              chainBaseManager.getAccountIndexStore().put(accountCapsule);
            });
  }

  public void initAccountHistoryBalance() {
    BlockCapsule genesis = chainBaseManager.getGenesisBlock();
    BlockBalanceTraceCapsule genesisBlockBalanceTraceCapsule =
        new BlockBalanceTraceCapsule(genesis);
    List<TransactionCapsule> transactionCapsules = genesis.getTransactions();
    for (TransactionCapsule transactionCapsule : transactionCapsules) {
      BalanceContract.TransferContract transferContract = transactionCapsule.getTransferContract();
      BalanceContract.TransactionBalanceTrace.Operation operation =
          BalanceContract.TransactionBalanceTrace.Operation.newBuilder()
              .setOperationIdentifier(0)
              .setAddress(transferContract.getToAddress())
              .setAmount(transferContract.getAmount())
              .build();

      BalanceContract.TransactionBalanceTrace transactionBalanceTrace =
          BalanceContract.TransactionBalanceTrace.newBuilder()
              .setTransactionIdentifier(transactionCapsule.getTransactionId().getByteString())
              .setType(TransferContract.name())
              .setStatus(SUCCESS.name())
              .addOperation(operation)
              .build();
      genesisBlockBalanceTraceCapsule.addTransactionBalanceTrace(transactionBalanceTrace);

      chainBaseManager.getAccountTraceStore().recordBalanceWithBlock(
          transferContract.getToAddress().toByteArray(), 0, transferContract.getAmount());
    }

    chainBaseManager.getBalanceTraceStore()
        .put(Longs.toByteArray(0), genesisBlockBalanceTraceCapsule);
  }

  /**
   * save witnesses into database.
   */
  private void initWitness() {
    final CommonParameter commonParameter = Args.getInstance();
    final GenesisBlock genesisBlockArg = commonParameter.getGenesisBlock();
    genesisBlockArg
        .getWitnesses()
        .forEach(
            key -> {
              byte[] keyAddress = key.getAddress();
              ByteString address = ByteString.copyFrom(keyAddress);

              final AccountCapsule accountCapsule;
              if (!chainBaseManager.getAccountStore().has(keyAddress)) {
                accountCapsule = new AccountCapsule(ByteString.EMPTY,
                    address, AccountType.AssetIssue, 0L);
              } else {
                accountCapsule = chainBaseManager.getAccountStore().getUnchecked(keyAddress);
              }
              accountCapsule.setIsWitness(true);
              chainBaseManager.getAccountStore().put(keyAddress, accountCapsule);

              final WitnessCapsule witnessCapsule =
                  new WitnessCapsule(address, key.getVoteCount(), key.getUrl());
              witnessCapsule.setIsJobs(true);
              chainBaseManager.getWitnessStore().put(keyAddress, witnessCapsule);
            });
  }

  /**
   * init auto-stop, check params
   */
  private void initAutoStop() {
    final long headNum = chainBaseManager.getHeadBlockNum();
    final long headTime = chainBaseManager.getHeadBlockTimeStamp();
    final long exitHeight = CommonParameter.getInstance().getShutdownBlockHeight();
    final long exitCount = CommonParameter.getInstance().getShutdownBlockCount();
    final CronExpression blockTime = Args.getInstance().getShutdownBlockTime();

    if (exitHeight > 0 && exitHeight < headNum) {
      throw new IllegalArgumentException(
          String.format("shutDownBlockHeight %d is less than headNum %d", exitHeight, headNum));
    }

    if (exitCount == 0) {
      throw new IllegalArgumentException(
          String.format("shutDownBlockCount %d is less than 1", exitCount));
    }

    if (blockTime != null && blockTime.getNextValidTimeAfter(new Date(headTime)) == null) {
      throw new IllegalArgumentException(
          String.format("shutDownBlockTime %s is illegal", blockTime));
    }

    if (exitHeight > 0 && exitCount > 0) {
      throw new IllegalArgumentException(
          String.format("shutDownBlockHeight %d and shutDownBlockCount %d set both",
              exitHeight, exitCount));
    }

    if (exitHeight > 0 && blockTime != null) {
      throw new IllegalArgumentException(
          String.format("shutDownBlockHeight %d and shutDownBlockTime %s set both",
              exitHeight, blockTime));
    }

    if (exitCount > 0 && blockTime != null) {
      throw new IllegalArgumentException(
          String.format("shutDownBlockCount %d and shutDownBlockTime %s set both",
              exitCount, blockTime));
    }

    if (exitHeight == headNum && (!Args.getInstance().isP2pDisable())) {
      logger.info("Auto-stop hit: shutDownBlockHeight: {}, currentHeaderNum: {}, exit now",
          exitHeight, headNum);
      System.exit(0);
    }

    if (exitCount > 0) {
      CommonParameter.getInstance().setShutdownBlockHeight(headNum + exitCount);
    }
    // init
    latestSolidityNumShutDown = CommonParameter.getInstance().getShutdownBlockHeight();
  }

  public AccountStore getAccountStore() {
    return chainBaseManager.getAccountStore();
  }

  public AccountAssetStore getAccountAssetStore() {
    return chainBaseManager.getAccountAssetStore();
  }

  public AccountIndexStore getAccountIndexStore() {
    return chainBaseManager.getAccountIndexStore();
  }

  void validateTapos(TransactionCapsule transactionCapsule) throws TaposException {
    byte[] refBlockHash = transactionCapsule.getInstance()
        .getRawData().getRefBlockHash().toByteArray();
    byte[] refBlockNumBytes = transactionCapsule.getInstance()
        .getRawData().getRefBlockBytes().toByteArray();
    try {
      byte[] blockHash = chainBaseManager.getRecentBlockStore().get(refBlockNumBytes).getData();
      if (!Arrays.equals(blockHash, refBlockHash)) {
        String str = String.format(
            "Tapos failed, different block hash, %s, %s , recent block %s, "
                + "solid block %s head block %s",
            ByteArray.toLong(refBlockNumBytes), Hex.toHexString(refBlockHash),
            Hex.toHexString(blockHash),
            chainBaseManager.getSolidBlockId().getString(),
            chainBaseManager.getHeadBlockId().getString());
        throw new TaposException(str);
      }
    } catch (ItemNotFoundException e) {
      String str = String
          .format("Tapos failed, block not found, ref block %s, %s , solid block %s head block %s",
              ByteArray.toLong(refBlockNumBytes), Hex.toHexString(refBlockHash),
              chainBaseManager.getSolidBlockId().getString(),
              chainBaseManager.getHeadBlockId().getString());
      throw new TaposException(str);
    }
  }

  void validateCommon(TransactionCapsule transactionCapsule)
      throws TransactionExpirationException, TooBigTransactionException {
    if (transactionCapsule.getData().length > Constant.TRANSACTION_MAX_BYTE_SIZE) {
      throw new TooBigTransactionException(String.format(
          "Too big transaction, the size is %d bytes", transactionCapsule.getData().length));
    }
    long transactionExpiration = transactionCapsule.getExpiration();
    long headBlockTime = chainBaseManager.getHeadBlockTimeStamp();
    if (transactionExpiration <= headBlockTime
        || transactionExpiration > headBlockTime + Constant.MAXIMUM_TIME_UNTIL_EXPIRATION) {
      throw new TransactionExpirationException(
          String.format(
          "Transaction expiration, transaction expiration time is %d, but headBlockTime is %d",
              transactionExpiration, headBlockTime));
    }
  }

  void validateDup(TransactionCapsule transactionCapsule) throws DupTransactionException {
    if (containsTransaction(transactionCapsule)) {
      throw new DupTransactionException(String.format("dup trans : %s ",
          transactionCapsule.getTransactionId()));
    }
  }

  private boolean containsTransaction(TransactionCapsule transactionCapsule) {
    return containsTransaction(transactionCapsule.getTransactionId().getBytes());
  }


  private boolean containsTransaction(byte[] transactionId) {
    if (transactionCache != null && !transactionCache.has(transactionId)) {
      // using the bloom filter only determines non-existent transaction
      return false;
    }

    return chainBaseManager.getTransactionStore()
        .has(transactionId);
  }

  /**
   * push transaction into pending.
   */
  public boolean pushTransaction(final TransactionCapsule trx)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, DupTransactionException, TaposException,
      TooBigTransactionException, TransactionExpirationException,
      ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException {

    if (isShieldedTransaction(trx.getInstance()) && !Args.getInstance()
        .isFullNodeAllowShieldedTransactionArgs()) {
      return true;
    }

    pushTransactionQueue.add(trx);
    Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, 1,
        MetricLabels.Gauge.QUEUE_QUEUED);
    try {
      if (!trx.validateSignature(chainBaseManager.getAccountStore(),
          chainBaseManager.getDynamicPropertiesStore())) {
        throw new ValidateSignatureException(String.format("trans sig validate failed, id: %s",
            trx.getTransactionId()));
      }

      synchronized (transactionLock) {
        while (true) {
          try {
            if (isBlockWaitingLock()) {
              TimeUnit.MILLISECONDS.sleep(SLEEP_FOR_WAIT_LOCK);
            } else {
              break;
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("The wait has been interrupted.");
          }
        }
        synchronized (this) {
          if (isShieldedTransaction(trx.getInstance())
                  && shieldedTransInPendingCounts.get() >= shieldedTransInPendingMaxCounts) {
            return false;
          }
          if (!session.valid()) {
            session.setValue(revokingStore.buildSession());
          }

          try (ISession tmpSession = revokingStore.buildSession()) {
            processTransaction(trx, null);
            trx.setTrxTrace(null);
            pendingTransactions.add(trx);
            Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, 1,
                    MetricLabels.Gauge.QUEUE_PENDING);
            tmpSession.merge();
          } catch (Throwable t) {
            t.printStackTrace();
            System.out.printf("Drop tx - %s, cause - %s%n",
                trx.getTransactionId().toString(), t.getMessage());
          }
          if (isShieldedTransaction(trx.getInstance())) {
            shieldedTransInPendingCounts.incrementAndGet();
          }
        }
      }
    } finally {
      if (pushTransactionQueue.remove(trx)) {
        Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, -1,
            MetricLabels.Gauge.QUEUE_QUEUED);
      }
    }
    return true;
  }

  public void consumeMultiSignFee(TransactionCapsule trx, TransactionTrace trace)
      throws AccountResourceInsufficientException {
    if (trx.getInstance().getSignatureCount() > 1) {
      long fee = getDynamicPropertiesStore().getMultiSignFee();

      List<Contract> contracts = trx.getInstance().getRawData().getContractList();
      for (Contract contract : contracts) {
        byte[] address = TransactionCapsule.getOwner(contract);
        AccountCapsule accountCapsule = getAccountStore().get(address);
        try {
          if (accountCapsule != null) {
            adjustBalance(getAccountStore(), accountCapsule, -fee);

            if (getDynamicPropertiesStore().supportBlackHoleOptimization()) {
              getDynamicPropertiesStore().burnTrx(fee);
            } else {
              adjustBalance(getAccountStore(), this.getAccountStore().getBlackhole(), +fee);
            }
          }
        } catch (BalanceInsufficientException e) {
          throw new AccountResourceInsufficientException(
              String.format("account %s insufficient balance[%d] to multiSign",
                  StringUtil.encode58Check(address), fee));
        }
      }

      trace.getReceipt().setMultiSignFee(fee);
    }
  }

  public void consumeMemoFee(TransactionCapsule trx, TransactionTrace trace)
      throws AccountResourceInsufficientException {
    if (trx.getInstance().getRawData().getData().isEmpty()) {
      // no memo
      return;
    }

    long fee = getDynamicPropertiesStore().getMemoFee();
    if (fee == 0) {
      return;
    }

    List<Contract> contracts = trx.getInstance().getRawData().getContractList();
    for (Contract contract : contracts) {
      byte[] address = TransactionCapsule.getOwner(contract);
      AccountCapsule accountCapsule = getAccountStore().get(address);
      try {
        if (accountCapsule != null) {
          adjustBalance(getAccountStore(), accountCapsule, -fee);

          if (getDynamicPropertiesStore().supportBlackHoleOptimization()) {
            getDynamicPropertiesStore().burnTrx(fee);
          } else {
            adjustBalance(getAccountStore(), this.getAccountStore().getBlackhole(), +fee);
          }
        }
      } catch (BalanceInsufficientException e) {
        throw new AccountResourceInsufficientException(
            String.format("account %s insufficient balance[%d] to memo fee",
                StringUtil.encode58Check(address), fee));
      }
    }

    trace.getReceipt().setMemoFee(fee);
  }

  public void consumeBandwidth(TransactionCapsule trx, TransactionTrace trace)
      throws ContractValidateException, AccountResourceInsufficientException,
      TooBigTransactionResultException {
    BandwidthProcessor processor = new BandwidthProcessor(chainBaseManager);
    processor.consume(trx, trace);
  }


  /**
   * when switch fork need erase blocks on fork branch.
   */
  public void eraseBlock() {
    session.reset();
    try {
      BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(
          getDynamicPropertiesStore().getLatestBlockHeaderHash());
      logger.info("Start to erase block: {}.", oldHeadBlock);
      khaosDb.pop();
      revokingStore.fastPop();
      logger.info("End to erase block: {}.", oldHeadBlock);
      oldHeadBlock.getTransactions().forEach(tc ->
          poppedTransactions.add(new TransactionCapsule(tc.getInstance())));
      Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, oldHeadBlock.getTransactions().size(),
          MetricLabels.Gauge.QUEUE_POPPED);

    } catch (ItemNotFoundException | BadItemException e) {
      logger.warn(e.getMessage(), e);
    }
  }

  public void pushVerifiedBlock(BlockCapsule block) throws ContractValidateException,
      ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
      TransactionExpirationException, TooBigTransactionException, DupTransactionException,
      TaposException, ValidateScheduleException, ReceiptCheckErrException,
      VMIllegalException, TooBigTransactionResultException, UnLinkedBlockException,
      NonCommonBlockException, BadNumberBlockException, BadBlockException, ZksnarkException,
      EventBloomException {
    block.generatedByMyself = true;
    long start = System.currentTimeMillis();
    pushBlock(block);
    logger.info("Push block cost: {} ms, blockNum: {}, blockHash: {}, trx count: {}.",
        System.currentTimeMillis() - start,
        block.getNum(),
        block.getBlockId(),
        block.getTransactions().size());
  }

  private void applyBlock(BlockCapsule block) throws ContractValidateException,
      ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
      TransactionExpirationException, TooBigTransactionException, DupTransactionException,
      TaposException, ValidateScheduleException, ReceiptCheckErrException,
      VMIllegalException, TooBigTransactionResultException,
      ZksnarkException, BadBlockException, EventBloomException {
    applyBlock(block, block.getTransactions());
  }

  private void applyBlock(BlockCapsule block, List<TransactionCapsule> txs)
      throws ContractValidateException, ContractExeException, ValidateSignatureException,
      AccountResourceInsufficientException, TransactionExpirationException,
      TooBigTransactionException, DupTransactionException, TaposException,
      ValidateScheduleException, ReceiptCheckErrException, VMIllegalException,
      TooBigTransactionResultException, ZksnarkException, BadBlockException, EventBloomException {
    processBlock(block, txs);
    chainBaseManager.getBlockStore().put(block.getBlockId().getBytes(), block);
    chainBaseManager.getBlockIndexStore().put(block.getBlockId());
    if (block.getTransactions().size() != 0) {
      chainBaseManager.getTransactionRetStore()
          .put(ByteArray.fromLong(block.getNum()), block.getResult());
    }

    updateFork(block);
    if (System.currentTimeMillis() - block.getTimeStamp() >= 60_000) {
      revokingStore.setMaxFlushCount(maxFlushCount);
      if (Args.getInstance().getShutdownBlockTime() != null
          && Args.getInstance().getShutdownBlockTime().getNextValidTimeAfter(
            new Date(block.getTimeStamp() - maxFlushCount * 1000 * 3L))
          .compareTo(new Date(block.getTimeStamp())) <= 0) {
        revokingStore.setMaxFlushCount(SnapshotManager.DEFAULT_MIN_FLUSH_COUNT);
      }
      if (latestSolidityNumShutDown > 0 && latestSolidityNumShutDown - block.getNum()
          <= maxFlushCount) {
        revokingStore.setMaxFlushCount(SnapshotManager.DEFAULT_MIN_FLUSH_COUNT);
      }
    } else {
      revokingStore.setMaxFlushCount(SnapshotManager.DEFAULT_MIN_FLUSH_COUNT);
    }
  }

  private void switchFork(BlockCapsule newHead)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      ValidateScheduleException, AccountResourceInsufficientException, TaposException,
      TooBigTransactionException, TooBigTransactionResultException, DupTransactionException,
      TransactionExpirationException, NonCommonBlockException, ReceiptCheckErrException,
      VMIllegalException, ZksnarkException, BadBlockException, EventBloomException {

    MetricsUtil.meterMark(MetricsKey.BLOCKCHAIN_FORK_COUNT);
    Metrics.counterInc(MetricKeys.Counter.BLOCK_FORK, 1, MetricLabels.ALL);

    Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> binaryTree;
    try {
      binaryTree =
          khaosDb.getBranch(
              newHead.getBlockId(), getDynamicPropertiesStore().getLatestBlockHeaderHash());
    } catch (NonCommonBlockException e) {
      Metrics.counterInc(MetricKeys.Counter.BLOCK_FORK, 1, MetricLabels.FAIL);
      MetricsUtil.meterMark(MetricsKey.BLOCKCHAIN_FAIL_FORK_COUNT);
      logger.info(
          "This is not the most recent common ancestor, "
              + "need to remove all blocks in the fork chain.");
      BlockCapsule tmp = newHead;
      while (tmp != null) {
        khaosDb.removeBlk(tmp.getBlockId());
        tmp = khaosDb.getBlock(tmp.getParentHash());
      }

      throw e;
    }

    if (CollectionUtils.isNotEmpty(binaryTree.getValue())) {
      while (!getDynamicPropertiesStore()
          .getLatestBlockHeaderHash()
          .equals(binaryTree.getValue().peekLast().getParentHash())) {
        reOrgContractTrigger();
        reOrgLogsFilter();
        eraseBlock();
      }
    }

    if (CollectionUtils.isNotEmpty(binaryTree.getKey())) {
      List<KhaosBlock> first = new ArrayList<>(binaryTree.getKey());
      Collections.reverse(first);
      for (KhaosBlock item : first) {
        Exception exception = null;
        // todo  process the exception carefully later
        try (ISession tmpSession = revokingStore.buildSession()) {
          applyBlock(item.getBlk().setSwitch(true));
          tmpSession.commit();
        } catch (AccountResourceInsufficientException
            | ValidateSignatureException
            | ContractValidateException
            | ContractExeException
            | TaposException
            | DupTransactionException
            | TransactionExpirationException
            | ReceiptCheckErrException
            | TooBigTransactionException
            | TooBigTransactionResultException
            | ValidateScheduleException
            | VMIllegalException
            | ZksnarkException
            | BadBlockException e) {
          logger.warn(e.getMessage(), e);
          exception = e;
          throw e;
        } finally {
          if (exception != null) {
            Metrics.counterInc(MetricKeys.Counter.BLOCK_FORK, 1, MetricLabels.FAIL);
            MetricsUtil.meterMark(MetricsKey.BLOCKCHAIN_FAIL_FORK_COUNT);
            logger.warn("Switch back because exception thrown while switching forks.", exception);
            first.forEach(khaosBlock -> khaosDb.removeBlk(khaosBlock.getBlk().getBlockId()));
            khaosDb.setHead(binaryTree.getValue().peekFirst());

            while (!getDynamicPropertiesStore()
                .getLatestBlockHeaderHash()
                .equals(binaryTree.getValue().peekLast().getParentHash())) {
              eraseBlock();
            }

            List<KhaosBlock> second = new ArrayList<>(binaryTree.getValue());
            Collections.reverse(second);
            for (KhaosBlock khaosBlock : second) {
              // todo  process the exception carefully later
              try (ISession tmpSession = revokingStore.buildSession()) {
                applyBlock(khaosBlock.getBlk().setSwitch(true));
                tmpSession.commit();
              } catch (AccountResourceInsufficientException
                  | ValidateSignatureException
                  | ContractValidateException
                  | ContractExeException
                  | TaposException
                  | DupTransactionException
                  | TransactionExpirationException
                  | TooBigTransactionException
                  | ValidateScheduleException
                  | ZksnarkException e) {
                logger.warn(e.getMessage(), e);
              }
            }
          }
        }
      }
    }

  }

  public List<TransactionCapsule> getVerifyTxs(BlockCapsule block) {

    if (pendingTransactions.size() == 0) {
      return block.getTransactions();
    }

    List<TransactionCapsule> txs = new ArrayList<>();
    Set<String> txIds = new HashSet<>();
    Set<String> multiAddresses = new HashSet<>();

    pendingTransactions.forEach(capsule -> {
      String txId = Hex.toHexString(capsule.getTransactionId().getBytes());
      if (isMultiSignTransaction(capsule.getInstance())) {
        String address = Hex.toHexString(capsule.getOwnerAddress());
        multiAddresses.add(address);
      } else {
        txIds.add(txId);
      }
    });

    block.getTransactions().forEach(capsule -> {
      String address = Hex.toHexString(capsule.getOwnerAddress());
      String txId = Hex.toHexString(capsule.getTransactionId().getBytes());
      if (multiAddresses.contains(address) || !txIds.contains(txId)) {
        txs.add(capsule);
      } else {
        capsule.setVerified(true);
      }
    });

    return txs;
  }

  /**
   * save a block.
   */
  public void pushBlock(final BlockCapsule block)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException,
      TaposException, TooBigTransactionException, TooBigTransactionResultException,
      DupTransactionException, TransactionExpirationException,
      BadNumberBlockException, BadBlockException, NonCommonBlockException,
      ReceiptCheckErrException, VMIllegalException, ZksnarkException, EventBloomException {
    setBlockWaitLock(true);
    try {
      synchronized (this) {
        Metrics.histogramObserve(blockedTimer.get());
        blockedTimer.remove();
        long headerNumber = getDynamicPropertiesStore().getLatestBlockHeaderNumber();
        if (block.getNum() <= headerNumber && khaosDb.containBlockInMiniStore(block.getBlockId())) {
          logger.info("Block {} is already exist.", block.getBlockId().getString());
          return;
        }
        final Histogram.Timer timer = Metrics.histogramStartTimer(
                MetricKeys.Histogram.BLOCK_PUSH_LATENCY);
        long start = System.currentTimeMillis();
        List<TransactionCapsule> txs = getVerifyTxs(block);
        logger.info("Block num: {}, re-push-size: {}, pending-size: {}, "
                        + "block-tx-size: {}, verify-tx-size: {}",
                block.getNum(), rePushTransactions.size(), pendingTransactions.size(),
                block.getTransactions().size(), txs.size());

        if (CommonParameter.getInstance().getShutdownBlockTime() != null
                && CommonParameter.getInstance().getShutdownBlockTime()
                .isSatisfiedBy(new Date(block.getTimeStamp()))) {
          latestSolidityNumShutDown = block.getNum();
        }

        try (PendingManager pm = new PendingManager(this)) {

          if (!block.generatedByMyself) {
            if (!block.calcMerkleRoot().equals(block.getMerkleRoot())) {
              logger.warn("Num: {}, the merkle root doesn't match, expect is {} , actual is {}.",
                  block.getNum(), block.getMerkleRoot(), block.calcMerkleRoot());
              throw new BadBlockException(CALC_MERKLE_ROOT_FAILED,
                      String.format("The merkle hash is not validated for %d", block.getNum()));
            }
            consensus.receiveBlock(block);
          }

          if (block.getTransactions().stream()
                  .filter(tran -> isShieldedTransaction(tran.getInstance()))
                  .count() > SHIELDED_TRANS_IN_BLOCK_COUNTS) {
            throw new BadBlockException(
                String.format("num: %d, shielded transaction count > %d",
                    block.getNum(), SHIELDED_TRANS_IN_BLOCK_COUNTS));
          }

          BlockCapsule newBlock;
          try {
            newBlock = this.khaosDb.push(block);
          } catch (UnLinkedBlockException e) {
            logger.error(
                    "LatestBlockHeaderHash: {}, latestBlockHeaderNumber: {}"
                            + ", latestSolidifiedBlockNum: {}.",
                    getDynamicPropertiesStore().getLatestBlockHeaderHash(),
                    getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
                    getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
            throw e;
          }

          // DB don't need lower block
          if (getDynamicPropertiesStore().getLatestBlockHeaderHash() == null) {
            if (newBlock.getNum() != 0) {
              return;
            }
          } else {
            if (newBlock.getNum() <= headerNumber) {
              return;
            }

            // switch fork
            if (!newBlock
                    .getParentHash()
                    .equals(getDynamicPropertiesStore().getLatestBlockHeaderHash())) {
              logger.warn("Switch fork! new head num = {}, block id = {}.",
                      newBlock.getNum(), newBlock.getBlockId());

              logger.warn(
                      "******** Before switchFork ******* push block: {}, new block: {}, "
                          + "dynamic head num: {}, dynamic head hash: {}, "
                          + "dynamic head timestamp: {}, khaosDb head: {}, "
                          + "khaosDb miniStore size: {}, khaosDb unlinkMiniStore size: {}.",
                  block, newBlock,
                  chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
                  chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderHash(),
                  chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp(),
                  khaosDb.getHead(), khaosDb.getMiniStore().size(),
                  khaosDb.getMiniUnlinkedStore().size());
              synchronized (forkLock) {
                switchFork(newBlock);
              }
              logger.info(SAVE_BLOCK, newBlock);

              logger.warn(
                  "******** After switchFork ******* push block: {}, new block: {}, "
                      + "dynamic head num: {}, dynamic head hash: {}, "
                      + "dynamic head timestamp: {}, khaosDb head: {}, "
                      + "khaosDb miniStore size: {}, khaosDb unlinkMiniStore size: {}.",
                  block, newBlock,
                  chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
                  chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderHash(),
                  chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp(),
                  khaosDb.getHead(), khaosDb.getMiniStore().size(),
                  khaosDb.getMiniUnlinkedStore().size());

              return;
            }
            try (ISession tmpSession = revokingStore.buildSession()) {

              long oldSolidNum =
                      chainBaseManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum();

              applyBlock(newBlock, txs);
              tmpSession.commit();
              // if event subscribe is enabled, post block trigger to queue
              postBlockTrigger(newBlock);
              // if event subscribe is enabled, post solidity trigger to queue
              postSolidityTrigger(oldSolidNum,
                      getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
            } catch (Throwable throwable) {
              logger.error(throwable.getMessage(), throwable);
              khaosDb.removeBlk(block.getBlockId());
              throw throwable;
            }
          }
          logger.info(SAVE_BLOCK, newBlock);
        }
        //clear ownerAddressSet
        if (CollectionUtils.isNotEmpty(ownerAddressSet)) {
          Set<String> result = new HashSet<>();
          for (TransactionCapsule transactionCapsule : rePushTransactions) {
            filterOwnerAddress(transactionCapsule, result);
          }
          for (TransactionCapsule transactionCapsule : pushTransactionQueue) {
            filterOwnerAddress(transactionCapsule, result);
          }
          ownerAddressSet.clear();
          ownerAddressSet.addAll(result);
        }

        long cost = System.currentTimeMillis() - start;
        MetricsUtil.meterMark(MetricsKey.BLOCKCHAIN_BLOCK_PROCESS_TIME, cost);

        logger.info("PushBlock block number: {}, cost/txs: {}/{} {}.",
                block.getNum(), cost, block.getTransactions().size(), cost > 1000);

        Metrics.histogramObserve(timer);
      }
    } finally {
      setBlockWaitLock(false);
    }
  }

  public void updateDynamicProperties(BlockCapsule block) {

    chainBaseManager.getDynamicPropertiesStore()
        .saveLatestBlockHeaderHash(block.getBlockId().getByteString());

    chainBaseManager.getDynamicPropertiesStore()
        .saveLatestBlockHeaderNumber(block.getNum());
    chainBaseManager.getDynamicPropertiesStore()
        .saveLatestBlockHeaderTimestamp(block.getTimeStamp());
    revokingStore.setMaxSize((int) (
        chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber()
            - chainBaseManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum()
            + 1));
    khaosDb.setMaxSize((int)
        (chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber()
            - chainBaseManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum()
            + 1));
    Metrics.gaugeSet(MetricKeys.Gauge.HEADER_HEIGHT, block.getNum());
    Metrics.gaugeSet(MetricKeys.Gauge.HEADER_TIME, block.getTimeStamp());
  }

  /**
   * Get the fork branch.
   */
  public LinkedList<BlockId> getBlockChainHashesOnFork(final BlockId forkBlockHash)
      throws NonCommonBlockException {
    final Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> branch =
        this.khaosDb.getBranch(
            getDynamicPropertiesStore().getLatestBlockHeaderHash(), forkBlockHash);

    LinkedList<KhaosBlock> blockCapsules = branch.getValue();

    if (blockCapsules.isEmpty()) {
      logger.info("Empty branch {}.", forkBlockHash);
      return Lists.newLinkedList();
    }

    LinkedList<BlockId> result = blockCapsules.stream()
        .map(KhaosBlock::getBlk)
        .map(BlockCapsule::getBlockId)
        .collect(Collectors.toCollection(LinkedList::new));

    result.add(blockCapsules.peekLast().getBlk().getParentBlockId());

    return result;
  }

  /**
   * Process transaction.
   */
  public TransactionInfo processTransaction(final TransactionCapsule trxCap, BlockCapsule blockCap)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, TransactionExpirationException,
      TooBigTransactionException, TooBigTransactionResultException,
      DupTransactionException, TaposException, ReceiptCheckErrException, VMIllegalException {
    if (trxCap == null) {
      return null;
    }
    Contract contract = trxCap.getInstance().getRawData().getContract(0);
    Sha256Hash txId = trxCap.getTransactionId();
    final Histogram.Timer requestTimer = Metrics.histogramStartTimer(
        MetricKeys.Histogram.PROCESS_TRANSACTION_LATENCY,
        Objects.nonNull(blockCap) ? MetricLabels.BLOCK : MetricLabels.TRX,
        contract.getType().name());

    long start = System.currentTimeMillis();

    if (Objects.nonNull(blockCap)) {
      chainBaseManager.getBalanceTraceStore().initCurrentTransactionBalanceTrace(trxCap);
    }

//    validateTapos(trxCap);
//    validateCommon(trxCap);

    if (trxCap.getInstance().getRawData().getContractList().size() != 1) {
      throw new ContractSizeNotEqualToOneException(
          String.format(
              "tx %s contract size should be exactly 1, this is extend feature ,actual :%d",
          txId, trxCap.getInstance().getRawData().getContractList().size()));
    }

//    validateDup(trxCap);

    if (!trxCap.validateSignature(chainBaseManager.getAccountStore(),
        chainBaseManager.getDynamicPropertiesStore())) {
      throw new ValidateSignatureException(
          String.format(" %s transaction signature validate failed", txId));
    }

    TransactionTrace trace = new TransactionTrace(trxCap, StoreFactory.getInstance(),
        new RuntimeImpl());
    trxCap.setTrxTrace(trace);

    consumeBandwidth(trxCap, trace);
    consumeMultiSignFee(trxCap, trace);
    consumeMemoFee(trxCap, trace);

    if (blockCap == null) {
      getDynamicPropertiesStore().saveUnfreezeDelayDays(1);
    }
    trace.init(blockCap, eventPluginLoaded);
    trace.checkIsConstant();
    trace.exec();

    if (Objects.nonNull(blockCap)) {
      trace.setResult();
      if (trace.checkNeedRetry()) {
        trace.init(blockCap, eventPluginLoaded);
        trace.checkIsConstant();
        trace.exec();
        trace.setResult();
        logger.info("Retry result when push: {}, for tx id: {}, tx resultCode in receipt: {}.",
            blockCap.hasWitnessSignature(), txId, trace.getReceipt().getResult());
      }
      if (blockCap.hasWitnessSignature()) {
        trace.check();
      }
    }

    trace.finalization();
    if (blockCap == null) {
      getDynamicPropertiesStore().saveUnfreezeDelayDays(0);
    }
    if (getDynamicPropertiesStore().supportVM()) {
      trxCap.setResult(trace.getTransactionContext());
      // Record something for smart contract
      if (trxCap.isContractType()) {
        // Record personal trx burn
        byte[] owner = trxCap.getOwnerAddress();
        ContractStateCapsule personalCap = chainBaseManager.getContractStateStore().getPersonalRecord(owner);
        if (personalCap == null) {
          personalCap = new ContractStateCapsule(0);
        }
        personalCap.addEnergyUsage(trace.getReceipt().getEnergyUsageTotal());
        personalCap.addEnergyPenaltyTotal(trace.getReceipt().getEnergyPenaltyTotal());
        personalCap.addTrxBurn(trace.getReceipt().getEnergyFee());
        personalCap.addTxTotalCount();

        // Record total energy consumption
        ContractStateCapsule totalCap = chainBaseManager.getContractStateStore().getTotalRecord();
        if (totalCap == null) {
          totalCap = new ContractStateCapsule(0);
        }
        totalCap.addEnergyUsage(trace.getReceipt().getEnergyUsageTotal());
        totalCap.addEnergyPenaltyTotal(trace.getReceipt().getEnergyPenaltyTotal());
        totalCap.addTrxBurn(trace.getReceipt().getEnergyFee());
        totalCap.addTxTotalCount();
        totalCap.addTxCount();

        // Get contract state
        byte[] addr = trace.getRuntimeResult().getContractAddress();
        ContractStateCapsule csc = getChainBaseManager().getContractStateStore().get(addr);
        if (csc == null) {
          csc = new ContractStateCapsule(getDynamicPropertiesStore().getCurrentCycleNumber());
        }

        // Record total energy usage and penalty whatever the execution result
        csc.addEnergyUsageTotal(trace.getReceipt().getEnergyUsageTotal());
        csc.addEnergyPenaltyTotal(trace.getReceipt().getEnergyPenaltyTotal());
        csc.addTrxBurn(trace.getReceipt().getEnergyFee());
        csc.addTxTotalCount();

        // Record usage and penalty for failed tx
        if (trace.getRuntimeResult().getResultCode() != SUCCESS) {
          csc.addEnergyUsageFailed(trace.getReceipt().getEnergyUsageTotal());
          csc.addEnergyPenaltyFailed(trace.getReceipt().getEnergyPenaltyTotal());
          csc.addTxFailedCount();

          if (trace.getReceipt().getResult() == OUT_OF_ENERGY && csc.getEnergyFactor() > 0) {
            csc.addTxOOECount();
            if (trxCap.getFeeLimit() <= 6200000 * (1 + (double) csc.getEnergyFactor() / 10000)) {
              logger.error("Feelimit is not enough ["
                  + Hex.toHexString(trxCap.getTransactionId().getBytes()) + "] "
                  + trxCap.getFeeLimit() + " [" + Time.getTimeString(blockCap.getTimeStamp()) + "]");
            }
          }
        }

        // Record trx penalty burn
        long energyByTrxBurned = trace.getReceipt().getEnergyUsageTotal()
            - trace.getReceipt().getEnergyUsage() - trace.getReceipt().getOriginEnergyUsage();

        energyByTrxBurned = Math.min(
            energyByTrxBurned,
            trace.getReceipt().getEnergyPenaltyTotal());

        totalCap.addTrxPenalty(energyByTrxBurned * getDynamicPropertiesStore().getEnergyFee());
        csc.addTrxPenalty(energyByTrxBurned * getDynamicPropertiesStore().getEnergyFee());

        // Save to db
        chainBaseManager.getContractStateStore().setPersonalRecord(owner, personalCap);
        chainBaseManager.getContractStateStore().setTotalRecord(totalCap);
        chainBaseManager.getContractStateStore().put(addr, csc);

        // Deal with USDT
        byte[] usdtAddr = Hex.decode("41a614f803B6FD780986A42c78Ec9c7f77e6DeD13C");

        if (trace.getRuntimeResult().getResultCode() == SUCCESS && Arrays.equals(usdtAddr, addr)) {
          try {
            String calldata = Hex.toHexString(trxCap.getInstance().getRawData().getContract(0).getParameter()
                    .unpack(SmartContractOuterClass.TriggerSmartContract.class).getData().toByteArray());
            if (calldata.startsWith("a9059cbb") || calldata.startsWith("23b872dd")) {
              BigInteger amount;
              if (calldata.startsWith("a9059cbb")) {
                amount = new BigInteger(calldata.substring(36 * 2, 68 * 2), 16);
              } else {
                amount = new BigInteger(calldata.substring(68 * 2, 100 * 2), 16);
              }

              if (amount.compareTo(BigInteger.valueOf(500000L)) > 0) {
                ContractStateCapsule bigCap = chainBaseManager.getContractStateStore().getBigUSDTRecord();
                if (bigCap == null) {
                  bigCap = new ContractStateCapsule(0);
                }
                bigCap.addEnergyUsageTotal(trace.getReceipt().getEnergyUsageTotal());
                bigCap.addEnergyPenaltyTotal(trace.getReceipt().getEnergyPenaltyTotal());
                bigCap.addEnergyUsage(trace.getReceipt().getEnergyUsage());
                bigCap.addTxTotalCount();
                chainBaseManager.getContractStateStore().setBigUSDTRecord(bigCap);
              } else {
                ContractStateCapsule smallCap = chainBaseManager.getContractStateStore().getSmallUSDTRecord();
                if (smallCap == null) {
                  smallCap = new ContractStateCapsule(0);
                }
                smallCap.addEnergyUsageTotal(trace.getReceipt().getEnergyUsageTotal());
                smallCap.addEnergyPenaltyTotal(trace.getReceipt().getEnergyPenaltyTotal());
                smallCap.addEnergyUsage(trace.getReceipt().getEnergyUsage());
                smallCap.addTxTotalCount();
                chainBaseManager.getContractStateStore().setSmallUSDTRecord(smallCap);
              }
            }
          } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
          }
        }
      } else {
        // Record tx count for non-contract tx
        ContractStateCapsule totalCap = chainBaseManager.getContractStateStore().getTotalRecord();
        if (totalCap == null) {
          totalCap = new ContractStateCapsule(0);
        }
        totalCap.addTxCount();

        switch (trxCap.getInstance().getRawData().getContract(0).getType()) {
          case TransferContract:
            totalCap.addTxTrxCount();
            break;
          case TransferAssetContract:
            totalCap.addTxTrc10Count();
            break;
        }

        chainBaseManager.getContractStateStore().setTotalRecord(totalCap);
      }
    }
    chainBaseManager.getTransactionStore().put(trxCap.getTransactionId().getBytes(), trxCap);

    Optional.ofNullable(transactionCache)
        .ifPresent(t -> t.put(trxCap.getTransactionId().getBytes(),
            new BytesCapsule(ByteArray.fromLong(trxCap.getBlockNum()))));

    TransactionInfoCapsule transactionInfo = TransactionUtil
        .buildTransactionInfoInstance(trxCap, blockCap, trace);

    // if event subscribe is enabled, post contract triggers to queue
    // only trigger when process block
    if (Objects.nonNull(blockCap) && !blockCap.isMerkleRootEmpty()) {
      String blockHash = blockCap.getBlockId().toString();
      postContractTrigger(trace, false, blockHash);
    }


    if (isMultiSignTransaction(trxCap.getInstance())) {
      ownerAddressSet.add(ByteArray.toHexString(trxCap.getOwnerAddress()));
    }

    if (Objects.nonNull(blockCap)) {
      chainBaseManager.getBalanceTraceStore()
          .updateCurrentTransactionStatus(
              trace.getRuntimeResult().getResultCode().name());
      chainBaseManager.getBalanceTraceStore().resetCurrentTransactionTrace();
    }
    //set the sort order
    trxCap.setOrder(transactionInfo.getFee());
    if (!eventPluginLoaded) {
      trxCap.setTrxTrace(null);
    }
    long cost = System.currentTimeMillis() - start;
    if (cost > 100) {
      String type = "broadcast";
      if (Objects.nonNull(blockCap)) {
        type = blockCap.hasWitnessSignature() ? "apply" : "pack";
      }
      logger.info("Process transaction {} cost {} ms during {}, {}",
             Hex.toHexString(transactionInfo.getId()), cost, type, contract.getType().name());
    }
    Metrics.histogramObserve(requestTimer);
    return transactionInfo.getInstance();
  }

  /**
   * Generate a block.
   */
  public BlockCapsule generateBlock(Miner miner, long blockTime, long timeout) {
    String address =  StringUtil.encode58Check(miner.getWitnessAddress().toByteArray());
    final Histogram.Timer timer = Metrics.histogramStartTimer(
        MetricKeys.Histogram.BLOCK_GENERATE_LATENCY, address);
    Metrics.histogramObserve(MetricKeys.Histogram.MINER_DELAY,
        (System.currentTimeMillis() - blockTime) / Metrics.MILLISECONDS_PER_SECOND, address);
    long postponedTrxCount = 0;
    logger.info("Generate block {} begin.", chainBaseManager.getHeadBlockNum() + 1);

    BlockCapsule blockCapsule = new BlockCapsule(chainBaseManager.getHeadBlockNum() + 1,
        chainBaseManager.getHeadBlockId(),
        blockTime, miner.getWitnessAddress());
    blockCapsule.generatedByMyself = true;
    session.reset();
    session.setValue(revokingStore.buildSession());

    accountStateCallBack.preExecute(blockCapsule);

    if (getDynamicPropertiesStore().getAllowMultiSign() == 1) {
      byte[] privateKeyAddress = miner.getPrivateKeyAddress().toByteArray();
      AccountCapsule witnessAccount = getAccountStore()
          .get(miner.getWitnessAddress().toByteArray());
      if (!Arrays.equals(privateKeyAddress, witnessAccount.getWitnessPermissionAddress())) {
        logger.warn("Witness permission is wrong.");
        return null;
      }
    }

    Set<String> accountSet = new HashSet<>();
    AtomicInteger shieldedTransCounts = new AtomicInteger(0);
    List<TransactionCapsule> toBePacked = new ArrayList<>();
    long currentSize = blockCapsule.getInstance().getSerializedSize();
    boolean isSort = Args.getInstance().isOpenTransactionSort();
    while (pendingTransactions.size() > 0 || rePushTransactions.size() > 0) {
      boolean fromPending = false;
      TransactionCapsule trx;
      if (pendingTransactions.size() > 0) {
        trx = pendingTransactions.peek();
        if (isSort) {
          TransactionCapsule trxRepush = rePushTransactions.peek();
          if (trxRepush == null || trx.getOrder() >= trxRepush.getOrder()) {
            fromPending = true;
          } else {
            trx = rePushTransactions.poll();
            Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, -1,
                MetricLabels.Gauge.QUEUE_REPUSH);
          }
        } else {
          fromPending = true;
        }
      } else {
        trx = rePushTransactions.poll();
        Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, -1,
            MetricLabels.Gauge.QUEUE_REPUSH);
      }

      if (fromPending) {
        pendingTransactions.poll();
        Metrics.gaugeInc(MetricKeys.Gauge.MANAGER_QUEUE, -1,
                MetricLabels.Gauge.QUEUE_PENDING);
      }

      if (trx == null) {
        //  transaction may be removed by rePushLoop.
        logger.warn("Trx is null, fromPending: {}, pending: {}, repush: {}.",
                fromPending, pendingTransactions.size(), rePushTransactions.size());
        continue;
      }
      if (System.currentTimeMillis() > timeout) {
        logger.warn("Processing transaction time exceeds the producing time {}.",
            System.currentTimeMillis());
        break;
      }

      // check the block size
      long trxPackSize = trx.computeTrxSizeForBlockMessage();
      if ((currentSize + trxPackSize)
          > ChainConstant.BLOCK_SIZE) {
        postponedTrxCount++;
        continue; // try pack more small trx
      }
      //shielded transaction
      Transaction transaction = trx.getInstance();
      if (isShieldedTransaction(transaction)
          && shieldedTransCounts.incrementAndGet() > SHIELDED_TRANS_IN_BLOCK_COUNTS) {
        continue;
      }
      //multi sign transaction
      byte[] owner = trx.getOwnerAddress();
      String ownerAddress = ByteArray.toHexString(owner);
      if (accountSet.contains(ownerAddress)) {
        continue;
      } else {
        if (isMultiSignTransaction(transaction)) {
          accountSet.add(ownerAddress);
        }
      }
      if (ownerAddressSet.contains(ownerAddress)) {
        trx.setVerified(false);
      }
      // apply transaction
      try (ISession tmpSession = revokingStore.buildSession()) {
        accountStateCallBack.preExeTrans();
        processTransaction(trx, blockCapsule);
        accountStateCallBack.exeTransFinish();
        tmpSession.merge();
        toBePacked.add(trx);
        currentSize += trxPackSize;
      } catch (Exception e) {
        logger.warn("Process trx {} failed when generating block {}, {}.", trx.getTransactionId(),
            blockCapsule.getNum(), e.getMessage());
      }
    }
    blockCapsule.addAllTransactions(toBePacked);
    accountStateCallBack.executeGenerateFinish();

    session.reset();

    blockCapsule.setMerkleRoot();
    blockCapsule.sign(miner.getPrivateKey());

    BlockCapsule capsule = new BlockCapsule(blockCapsule.getInstance());
    capsule.generatedByMyself = true;
    Metrics.histogramObserve(timer);
    logger.info("Generate block {} success, trxs:{}, pendingCount: {}, rePushCount: {},"
                    + " postponedCount: {}, blockSize: {} B",
            capsule.getNum(), capsule.getTransactions().size(),
            pendingTransactions.size(), rePushTransactions.size(), postponedTrxCount,
            capsule.getSerializedSize());
    return capsule;
  }

  private void filterOwnerAddress(TransactionCapsule transactionCapsule, Set<String> result) {
    byte[] owner = transactionCapsule.getOwnerAddress();
    String ownerAddress = ByteArray.toHexString(owner);
    if (ownerAddressSet.contains(ownerAddress)) {
      result.add(ownerAddress);
    }
  }

  private boolean isMultiSignTransaction(Transaction transaction) {
    Contract contract = transaction.getRawData().getContract(0);
    switch (contract.getType()) {
      case AccountPermissionUpdateContract: {
        return true;
      }
      default:
    }
    return false;
  }

  private boolean isShieldedTransaction(Transaction transaction) {
    Contract contract = transaction.getRawData().getContract(0);
    switch (contract.getType()) {
      case ShieldedTransferContract: {
        return true;
      }
      default:
        return false;
    }
  }

  public TransactionStore getTransactionStore() {
    return chainBaseManager.getTransactionStore();
  }

  public TransactionHistoryStore getTransactionHistoryStore() {
    return chainBaseManager.getTransactionHistoryStore();
  }

  public TransactionRetStore getTransactionRetStore() {
    return chainBaseManager.getTransactionRetStore();
  }

  public BlockStore getBlockStore() {
    return chainBaseManager.getBlockStore();
  }

  /**
   * process block.
   */
  private void processBlock(BlockCapsule block, List<TransactionCapsule> txs)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, TaposException, TooBigTransactionException,
      DupTransactionException, TransactionExpirationException, ValidateScheduleException,
      ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException,
      ZksnarkException, BadBlockException, EventBloomException {
    // todo set revoking db max size.

    // checkWitness
    if (!consensus.validBlock(block)) {
      throw new ValidateScheduleException("validateWitnessSchedule error");
    }

    chainBaseManager.getBalanceTraceStore().initCurrentBlockBalanceTrace(block);

    //reset BlockEnergyUsage
    chainBaseManager.getDynamicPropertiesStore().saveBlockEnergyUsage(0);
    //parallel check sign
    if (!block.generatedByMyself) {
      try {
        preValidateTransactionSign(txs);
      } catch (InterruptedException e) {
        logger.error("Parallel check sign interrupted exception! block info: {}.", block, e);
        Thread.currentThread().interrupt();
      }
    }

    TransactionRetCapsule transactionRetCapsule =
        new TransactionRetCapsule(block);
    try {
      merkleContainer.resetCurrentMerkleTree();
      accountStateCallBack.preExecute(block);
      List<TransactionInfo> results = new ArrayList<>();
      long num = block.getNum();
      for (TransactionCapsule transactionCapsule : block.getTransactions()) {
        transactionCapsule.setBlockNum(num);
        if (block.generatedByMyself) {
          transactionCapsule.setVerified(true);
        }
        accountStateCallBack.preExeTrans();
        TransactionInfo result = processTransaction(transactionCapsule, block);
        accountStateCallBack.exeTransFinish();
        if (Objects.nonNull(result)) {
          results.add(result);
        }
      }
      transactionRetCapsule.addAllTransactionInfos(results);
      accountStateCallBack.executePushFinish();
    } finally {
      accountStateCallBack.exceptionFinish();
    }
    merkleContainer.saveCurrentMerkleTreeAsBestMerkleTree(block.getNum());
    block.setResult(transactionRetCapsule);
    if (getDynamicPropertiesStore().getAllowAdaptiveEnergy() == 1) {
      EnergyProcessor energyProcessor = new EnergyProcessor(
          chainBaseManager.getDynamicPropertiesStore(), chainBaseManager.getAccountStore());
      energyProcessor.updateTotalEnergyAverageUsage();
      energyProcessor.updateAdaptiveTotalEnergyLimit();
    }

    payReward(block);

    boolean flag = chainBaseManager.getDynamicPropertiesStore().getNextMaintenanceTime()
        <= block.getTimeStamp();
    if (flag) {
      proposalController.processProposals();
      chainBaseManager.getForkController().reset();

      // Do cycle stats within day
      long cycleNum = getDynamicPropertiesStore().getCurrentCycleNumber();
      new Thread(() -> {
        doDynamicEnergyDayStats(cycleNum, true);
//        doDynamicEnergyCycleStats(cycleNum, false);
      }).start();
    }

    if (!consensus.applyBlock(block)) {
      throw new BadBlockException("consensus apply block failed");
    }

    if (flag) {
      chainBaseManager.getForkController().reset();
    }

//    updateTransHashCache(block);
//    updateRecentBlock(block);
//    updateRecentTransaction(block);
    updateDynamicProperties(block);

    chainBaseManager.getBalanceTraceStore().resetCurrentBlockTrace();

    if (CommonParameter.getInstance().isJsonRpcFilterEnabled()) {
      Bloom blockBloom = chainBaseManager.getSectionBloomStore()
          .initBlockSection(transactionRetCapsule);
      chainBaseManager.getSectionBloomStore().write(block.getNum());
      block.setBloom(blockBloom);
    }
  }

  public void doDynamicEnergyCycleStats(long cycleNum, boolean forcedReport) {
    if (Args.getInstance().defiSlackWebhook.isEmpty()) {
      return;
    }
    String cycleNumber = String.valueOf(cycleNum);
    ContractStateStore css = getChainBaseManager().getContractStateStore();
    Map<WrappedByteArray, ContractStateCapsule> contracts =
        css.prefixQuery((cycleNumber + "-").getBytes());
    List<Map.Entry<WrappedByteArray, ContractStateCapsule>> list =
        new LinkedList<>(contracts.entrySet());
    list.sort((o1, o2) ->
        Long.compare(o2.getValue().getEnergyUsage(), o1.getValue().getEnergyUsage()));
    StringBuilder sb = new StringBuilder();
    if (list.size() > 0) {
      DecimalFormat df = new DecimalFormat("#,###");
      ContractStateCapsule totalCap = list.get(0).getValue();
      sb.append("> Cycle-").append(cycleNumber).append(": ");
      sb.append(String.format("`TotalEnergyUsage`: %s, ",
          df.format(totalCap.getEnergyUsage())));
      sb.append(String.format("`TotalEnergyPenalty`: %s, ",
          df.format(totalCap.getEnergyPenaltyTotal())));
      sb.append(String.format("`TotalTrxBurn`: %s, ",
          df.format(totalCap.getTrxBurn() / 1000000)));
      sb.append(String.format("`TotalTxCount`: %s\n",
          df.format(totalCap.getTxTotalCount())));
      Map.Entry<WrappedByteArray, ContractStateCapsule> entry = list.get(1);
      byte[] key = Arrays.copyOfRange(entry.getKey().getBytes(),
          cycleNumber.length() + 1, cycleNumber.length() + 22);
      sb.append("> Top-1").append(": ")
          .append(StringUtil.encode58Check(key)).append("\n");
      sb.append(entry.getValue().toSlackMsg());
    }
    logger.error(sb.toString());
    if (sb.length() > 0 && (forcedReport || System.currentTimeMillis()
        - getDynamicPropertiesStore().getLatestBlockHeaderTimestamp() < 60_000L)) {
      NetUtil.post(Args.getInstance().defiSlackWebhook, String.format("{\"text\":\"%s\"}", sb));
    }
  }

  public void doDynamicEnergyDayStats(long cycleNum, boolean forcedReport) {
    // Save total stake info
    ContractStateCapsule totalCap = chainBaseManager.getContractStateStore().getTotalRecord();
    totalCap.setBandwidthStake(getDynamicPropertiesStore().getTotalNetWeight());
    totalCap.setEnergyStake(getDynamicPropertiesStore().getTotalEnergyWeight());
    totalCap.setBandwidthStake2(getDynamicPropertiesStore().getTotalNetWeight2());
    totalCap.setEnergyStake2(getDynamicPropertiesStore().getTotalEnergyWeight2());
    chainBaseManager.getContractStateStore().setTotalRecord(totalCap);

    if (Args.getInstance().trackerSlackWebhook.isEmpty()) {
      return;
    }
    byte[] addr = Commons.decodeFromBase58Check("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
    ContractStateStore css = getChainBaseManager().getContractStateStore();

    StringBuilder sb = new StringBuilder();
    sb.append(String.format("> *最近一天 (#%d 维护期往前 4 个维护期):*\n", cycleNum));

    DecimalFormat df = new DecimalFormat("#,###");
    ContractStateCapsule todayTotal = css.getDayState(cycleNum, "total".getBytes());
    ContractStateCapsule yesterdayTotal = css.getDayState(cycleNum - 4, "total".getBytes());
    ContractStateCapsule monthAvgTotal = css.getMonthAvgState(cycleNum - 4, "total".getBytes());
    sb.append(String.format("> 总能量燃烧: `%s` TRX, 相对昨日: `%s`, 相对基准: `%s`\n",
        df.format(todayTotal.getTrxBurn() / 1_000_000L),
        formatPercent(todayTotal.getTrxBurn(), yesterdayTotal.getTrxBurn()),
        formatPercent((long) (todayTotal.getTrxBurn() / 1e6), 10_075_528L)));
    ContractStateCapsule today = css.getDayState(cycleNum, addr);
    ContractStateCapsule yesterday = css.getDayState(cycleNum - 4, addr);
    ContractStateCapsule monthAvg = css.getMonthAvgState(cycleNum - 4, addr);
    sb.append(String.format("> USDT 燃烧: `%s` TRX, 相对昨日: `%s`, 相对基准: `%s` [占比 %.2f%%]\n",
        df.format(today.getTrxBurn() / 1_000_000L),
        formatPercent(today.getTrxBurn(), yesterday.getTrxBurn()),
        formatPercent((long) (today.getTrxBurn() / 1e6), 8_939_720L),
        (double) today.getTrxBurn() / todayTotal.getTrxBurn() * 100));
    sb.append(String.format("> USDT 能量: `%.2f` B, 相对昨日: `%s`, 相对基准: `%s`\n",
        (double) today.getEnergyUsageTotal() / 1_000_000_000L,
        formatPercent(today.getEnergyUsageTotal(), yesterday.getEnergyUsageTotal()),
        formatPercent(today.getEnergyUsageTotal(), 41_817_570_852L)));
    sb.append(String.format("> USDT 惩罚: `%.2f` B, 相对昨日: `%s`, 相对基准: `%s` [惩罚比例 %.2f%%, 昨日 %.2f%%]\n",
        (double) today.getEnergyPenaltyTotal() / 1_000_000_000L,
        formatPercent(today.getEnergyPenaltyTotal(), yesterday.getEnergyPenaltyTotal()),
        formatPercent(today.getEnergyPenaltyTotal(), monthAvg.getEnergyPenaltyTotal()),
        (double) today.getEnergyPenaltyTotal()
            / (today.getEnergyUsageTotal() - today.getEnergyPenaltyTotal()) * 100,
        (double) yesterday.getEnergyPenaltyTotal()
            / (yesterday.getEnergyUsageTotal() - yesterday.getEnergyPenaltyTotal()) * 100));
    sb.append(String.format("> USDT 交易: 共 `%s` 笔 / `%s` out_energy (%.2f%%)/ `%s` other fails (%.2f%%)\n",
        df.format(today.getTxTotalCount()),
        df.format(today.getTxOOECount()),
        (double) today.getTxOOECount() / today.getTxTotalCount() * 100,
        df.format(today.getTxFailedCount() - today.getTxOOECount()),
        (double) (today.getTxFailedCount() - today.getTxOOECount()) / today.getTxTotalCount() * 100));
    int gasPrice = NetUtil.getGasPrice();
    long energyPrice = getDynamicPropertiesStore().getEnergyFee();
    double trxPrice = NetUtil.getPrice("TRX");
    double ethPrice = NetUtil.getPrice("ETH");
    long factor = 0;
    ContractStateCapsule csc = css.get(addr);
    if (csc != null) {
      factor = csc.getEnergyFactor();
    }
    long usdPerEnergy = (long) (trxPrice * energyPrice * (1 + factor / 10_000L));
    long usdPerGas = (long) (ethPrice * gasPrice);
    css.recordEnergyAndGasPrice(usdPerEnergy, usdPerGas);
    sb.append(String.format("> USDT 当前手续费: `%.2f$` - `%.2f$` @TRON / `%.2f$` - `%.2f$` @ETH\n",
        trxPrice * energyPrice * 14650 * (1 + factor / 10000.0) / 1e6,
        trxPrice * energyPrice * 29650 * (1 + factor / 10000.0) / 1e6,
        ethPrice * gasPrice * 41309 / 1e9,
        ethPrice * gasPrice * 63209 / 1e9));
    sb.append(String.format("> USDT 因子: 当前 `%.4f`", (double) factor / 10000));
    if (sb.length() > 0 && (forcedReport || System.currentTimeMillis()
        - getDynamicPropertiesStore().getLatestBlockHeaderTimestamp() < 60_000L)) {
      logger.error(sb.toString());
      NetUtil.post(Args.getInstance().trackerSlackWebhook, String.format("{\"text\":\"%s\"}", sb));
    }
  }

  private String formatPercent(long newValue, long oldValue) {
    if (newValue > oldValue) {
      return "+" + String.format("%.2f%%", (double) (newValue - oldValue) / oldValue * 100);
    } else {
      return "-" + String.format("%.2f%%", (double) (oldValue - newValue) / oldValue * 100);
    }
  }

  private void payReward(BlockCapsule block) {
    WitnessCapsule witnessCapsule =
        chainBaseManager.getWitnessStore().getUnchecked(block.getInstance().getBlockHeader()
            .getRawData().getWitnessAddress().toByteArray());
    if (getDynamicPropertiesStore().allowChangeDelegation()) {
      mortgageService.payBlockReward(witnessCapsule.getAddress().toByteArray(),
          getDynamicPropertiesStore().getWitnessPayPerBlock());
      mortgageService.payStandbyWitness();

      if (chainBaseManager.getDynamicPropertiesStore().supportTransactionFeePool()) {
        long transactionFeeReward = Math
            .floorDiv(chainBaseManager.getDynamicPropertiesStore().getTransactionFeePool(),
                Constant.TRANSACTION_FEE_POOL_PERIOD);
        mortgageService.payTransactionFeeReward(witnessCapsule.getAddress().toByteArray(),
            transactionFeeReward);
        chainBaseManager.getDynamicPropertiesStore().saveTransactionFeePool(
            chainBaseManager.getDynamicPropertiesStore().getTransactionFeePool()
                - transactionFeeReward);
      }
    } else {
      byte[] witness = block.getWitnessAddress().toByteArray();
      AccountCapsule account = getAccountStore().get(witness);
      account.setAllowance(account.getAllowance()
          + chainBaseManager.getDynamicPropertiesStore().getWitnessPayPerBlock());

      if (chainBaseManager.getDynamicPropertiesStore().supportTransactionFeePool()) {
        long transactionFeeReward = Math
            .floorDiv(chainBaseManager.getDynamicPropertiesStore().getTransactionFeePool(),
                Constant.TRANSACTION_FEE_POOL_PERIOD);
        account.setAllowance(account.getAllowance() + transactionFeeReward);
        chainBaseManager.getDynamicPropertiesStore().saveTransactionFeePool(
            chainBaseManager.getDynamicPropertiesStore().getTransactionFeePool()
                - transactionFeeReward);
      }

      getAccountStore().put(account.createDbKey(), account);
    }
  }

  private void postSolidityLogContractTrigger(Long blockNum, Long lastSolidityNum) {
    if (blockNum > lastSolidityNum) {
      return;
    }
    BlockingQueue contractLogTriggersQueue = Args.getSolidityContractLogTriggerMap()
        .get(blockNum);
    while (!contractLogTriggersQueue.isEmpty()) {
      ContractLogTrigger triggerCapsule = (ContractLogTrigger) contractLogTriggersQueue.poll();
      if (triggerCapsule == null) {
        break;
      }
      if (containsTransaction(ByteArray.fromHexString(triggerCapsule
          .getTransactionId()))) {
        triggerCapsule.setTriggerName(Trigger.SOLIDITYLOG_TRIGGER_NAME);
        EventPluginLoader.getInstance().postSolidityLogTrigger(triggerCapsule);
      } else {
        logger.error("PostSolidityLogContractTrigger txId = {} not contains transaction.",
            triggerCapsule.getTransactionId());
      }
    }
    Args.getSolidityContractLogTriggerMap().remove(blockNum);
  }

  private void postSolidityEventContractTrigger(Long blockNum, Long lastSolidityNum) {
    if (blockNum > lastSolidityNum) {
      return;
    }
    BlockingQueue contractEventTriggersQueue = Args.getSolidityContractEventTriggerMap()
        .get(blockNum);
    while (!contractEventTriggersQueue.isEmpty()) {
      ContractEventTrigger triggerCapsule = (ContractEventTrigger) contractEventTriggersQueue
          .poll();
      if (triggerCapsule == null) {
        break;
      }
      if (containsTransaction(ByteArray.fromHexString(triggerCapsule
          .getTransactionId()))) {
        triggerCapsule.setTriggerName(Trigger.SOLIDITYEVENT_TRIGGER_NAME);
        EventPluginLoader.getInstance().postSolidityEventTrigger(triggerCapsule);
      }
    }
    Args.getSolidityContractEventTriggerMap().remove(blockNum);
  }

  private void updateTransHashCache(BlockCapsule block) {
    for (TransactionCapsule transactionCapsule : block.getTransactions()) {
      this.transactionIdCache.put(transactionCapsule.getTransactionId(), true);
    }
  }

  public void updateRecentBlock(BlockCapsule block) {
    chainBaseManager.getRecentBlockStore().put(ByteArray.subArray(
        ByteArray.fromLong(block.getNum()), 6, 8),
        new BytesCapsule(ByteArray.subArray(block.getBlockId().getBytes(), 8, 16)));
  }

  public void updateRecentTransaction(BlockCapsule block) {
    List list = new ArrayList<>();
    block.getTransactions().forEach(capsule -> {
      list.add(capsule.getTransactionId().toString());
    });
    RecentTransactionItem item = new RecentTransactionItem(block.getNum(), list);
    chainBaseManager.getRecentTransactionStore().put(
            ByteArray.subArray(ByteArray.fromLong(block.getNum()), 6, 8),
            new BytesCapsule(JsonUtil.obj2Json(item).getBytes()));
  }

  public void updateFork(BlockCapsule block) {
    int blockVersion = block.getInstance().getBlockHeader().getRawData().getVersion();
    if (blockVersion > ChainConstant.BLOCK_VERSION) {
      logger.warn("Newer block version found: {}, YOU MUST UPGRADE java-tron!", blockVersion);
    }
    chainBaseManager.getForkController().update(block);
  }

  public long getSyncBeginNumber() {
    logger.info("HeadNumber: {}, syncBeginNumber: {}, solidBlockNumber: {}.",
        chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
        chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber()
            - revokingStore.size(),
        chainBaseManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
    return chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber()
        - revokingStore.size();
  }

  public AssetIssueStore getAssetIssueStore() {
    return chainBaseManager.getAssetIssueStore();
  }


  public AssetIssueV2Store getAssetIssueV2Store() {
    return chainBaseManager.getAssetIssueV2Store();
  }

  public AccountIdIndexStore getAccountIdIndexStore() {
    return chainBaseManager.getAccountIdIndexStore();
  }

  public NullifierStore getNullifierStore() {
    return chainBaseManager.getNullifierStore();
  }

  public void closeAllStore() {
    logger.info("******** Begin to close db. ********");
    chainBaseManager.closeAllStore();
    validateSignService.shutdown();
    logger.info("******** End to close db. ********");
  }

  public void closeOneStore(ITronChainBase database) {
    logger.info("******** Begin to close {}. ********", database.getName());
    try {
      database.close();
    } catch (Exception e) {
      logger.info("Failed to close {}.", database.getName(), e);
    } finally {
      logger.info("******** End to close {}. ********", database.getName());
    }
  }

  public boolean isTooManyPending() {
    return getPendingTransactions().size() + getRePushTransactions().size()
        > maxTransactionPendingSize;
  }

  private void preValidateTransactionSign(List<TransactionCapsule> txs)
      throws InterruptedException, ValidateSignatureException {
    int transSize = txs.size();
    if (transSize <= 0) {
      return;
    }
    Histogram.Timer requestTimer = Metrics.histogramStartTimer(
        MetricKeys.Histogram.VERIFY_SIGN_LATENCY, MetricLabels.TRX);
    try {
      CountDownLatch countDownLatch = new CountDownLatch(transSize);
      List<Future<Boolean>> futures = new ArrayList<>(transSize);

      for (TransactionCapsule transaction : txs) {
        Future<Boolean> future = validateSignService
            .submit(new ValidateSignTask(transaction, countDownLatch, chainBaseManager));
        futures.add(future);
      }
      countDownLatch.await();

      for (Future<Boolean> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          throw new ValidateSignatureException(e.getCause().getMessage());
        }
      }
    } finally {
      Metrics.histogramObserve(requestTimer);
    }
  }

  public void rePush(TransactionCapsule tx) {
    if (containsTransaction(tx)) {
      return;
    }

    try {
      this.pushTransaction(tx);
    } catch (ValidateSignatureException | ContractValidateException | ContractExeException
        | AccountResourceInsufficientException | VMIllegalException e) {
      logger.debug(e.getMessage(), e);
    } catch (DupTransactionException e) {
      logger.debug("Pending manager: dup trans", e);
    } catch (TaposException e) {
      logger.debug("Pending manager: tapos exception", e);
    } catch (TooBigTransactionException e) {
      logger.debug("Pending manager: too big transaction", e);
    } catch (TransactionExpirationException e) {
      logger.debug("Pending manager: expiration transaction", e);
    } catch (ReceiptCheckErrException e) {
      logger.debug("Pending manager: outOfSlotTime transaction", e);
    } catch (TooBigTransactionResultException e) {
      logger.debug("Pending manager: too big transaction result", e);
    }
  }

  public long getHeadBlockNum() {
    return getDynamicPropertiesStore().getLatestBlockHeaderNumber();
  }

  public void setCursor(Chainbase.Cursor cursor) {
    if (cursor == Chainbase.Cursor.PBFT) {
      long headNum = getHeadBlockNum();
      long pbftNum = chainBaseManager.getCommonDataBase().getLatestPbftBlockNum();
      revokingStore.setCursor(cursor, headNum - pbftNum);
    } else {
      revokingStore.setCursor(cursor);
    }
  }

  public void resetCursor() {
    revokingStore.setCursor(Chainbase.Cursor.HEAD, 0L);
  }

  private void startEventSubscribing() {

    try {
      eventPluginLoaded = EventPluginLoader.getInstance()
          .start(Args.getInstance().getEventPluginConfig());

      if (!eventPluginLoaded) {
        logger.error("Failed to load eventPlugin.");
      }

      FilterQuery eventFilter = Args.getInstance().getEventFilter();
      if (!Objects.isNull(eventFilter)) {
        EventPluginLoader.getInstance().setFilterQuery(eventFilter);
      }

    } catch (Exception e) {
      logger.error("{}", e);
    }
  }

  private void postSolidityFilter(final long oldSolidNum, final long latestSolidifiedBlockNumber) {
    if (oldSolidNum >= latestSolidifiedBlockNumber) {
      logger.warn("Post solidity filter failed, oldSolidity: {} >= latestSolidity: {}.",
          oldSolidNum, latestSolidifiedBlockNumber);
      return;
    }

    List<BlockCapsule> capsuleList = getContinuousBlockCapsule(latestSolidifiedBlockNumber);
    for (BlockCapsule blockCapsule : capsuleList) {
      postBlockFilter(blockCapsule, true);
      postLogsFilter(blockCapsule, true, false);
    }
  }

  private void postSolidityTrigger(final long oldSolidNum, final long latestSolidifiedBlockNumber) {
    if (eventPluginLoaded && EventPluginLoader.getInstance().isSolidityLogTriggerEnable()) {
      for (Long i : Args.getSolidityContractLogTriggerMap().keySet()) {
        postSolidityLogContractTrigger(i, latestSolidifiedBlockNumber);
      }
    }

    if (eventPluginLoaded && EventPluginLoader.getInstance().isSolidityEventTriggerEnable()) {
      for (Long i : Args.getSolidityContractEventTriggerMap().keySet()) {
        postSolidityEventContractTrigger(i, latestSolidifiedBlockNumber);
      }
    }

    if (eventPluginLoaded && EventPluginLoader.getInstance().isSolidityTriggerEnable()) {
      List<BlockCapsule> capsuleList = getContinuousBlockCapsule(latestSolidifiedBlockNumber);
      for (BlockCapsule blockCapsule : capsuleList) {
        SolidityTriggerCapsule solidityTriggerCapsule
            = new SolidityTriggerCapsule(blockCapsule.getNum());//unique key
        solidityTriggerCapsule.setTimeStamp(blockCapsule.getTimeStamp());
        boolean result = triggerCapsuleQueue.offer(solidityTriggerCapsule);
        if (!result) {
          logger.info("Too many trigger, lost solidified trigger, block number: {}.",
              blockCapsule.getNum());
        }
      }
    }

    if (CommonParameter.getInstance().isJsonRpcHttpSolidityNodeEnable()) {
      postSolidityFilter(oldSolidNum, latestSolidifiedBlockNumber);
    }
    lastUsedSolidityNum = latestSolidifiedBlockNumber;
  }

  private void processTransactionTrigger(BlockCapsule newBlock) {
    List<TransactionCapsule> transactionCapsuleList = newBlock.getTransactions();

    // need to set eth compatible data from transactionInfoList
    if (EventPluginLoader.getInstance().isTransactionLogTriggerEthCompatible()
          && newBlock.getNum() != 0) {
      TransactionInfoList transactionInfoList = TransactionInfoList.newBuilder().build();
      TransactionInfoList.Builder transactionInfoListBuilder = TransactionInfoList.newBuilder();

      try {
        TransactionRetCapsule result = chainBaseManager.getTransactionRetStore()
            .getTransactionInfoByBlockNum(ByteArray.fromLong(newBlock.getNum()));

        if (!Objects.isNull(result) && !Objects.isNull(result.getInstance())) {
          result.getInstance().getTransactioninfoList().forEach(
              transactionInfoListBuilder::addTransactionInfo
          );

          transactionInfoList = transactionInfoListBuilder.build();
        }
      } catch (BadItemException e) {
        logger.error("PostBlockTrigger getTransactionInfoList blockNum = {}, error is {}.",
            newBlock.getNum(), e.getMessage());
      }

      if (transactionCapsuleList.size() == transactionInfoList.getTransactionInfoCount()) {
        long cumulativeEnergyUsed = 0;
        long cumulativeLogCount = 0;
        long energyUnitPrice = chainBaseManager.getDynamicPropertiesStore().getEnergyFee();

        for (int i = 0; i < transactionCapsuleList.size(); i++) {
          TransactionInfo transactionInfo = transactionInfoList.getTransactionInfo(i);
          TransactionCapsule transactionCapsule = transactionCapsuleList.get(i);
          // reset block num to ignore value is -1
          transactionCapsule.setBlockNum(newBlock.getNum());

          cumulativeEnergyUsed += postTransactionTrigger(transactionCapsule, newBlock, i,
              cumulativeEnergyUsed, cumulativeLogCount, transactionInfo, energyUnitPrice);

          cumulativeLogCount += transactionInfo.getLogCount();
        }
      } else {
        logger.error("PostBlockTrigger blockNum = {} has no transactions or {}.",
            newBlock.getNum(),
            "the sizes of transactionInfoList and transactionCapsuleList are not equal");
        for (TransactionCapsule e : newBlock.getTransactions()) {
          postTransactionTrigger(e, newBlock);
        }
      }
    } else {
      for (TransactionCapsule e : newBlock.getTransactions()) {
        postTransactionTrigger(e, newBlock);
      }
    }
  }

  private void reOrgLogsFilter() {
    if (CommonParameter.getInstance().isJsonRpcHttpFullNodeEnable()) {
      logger.info("Switch fork occurred, post reOrgLogsFilter.");

      try {
        BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(
            getDynamicPropertiesStore().getLatestBlockHeaderHash());
        postLogsFilter(oldHeadBlock, false, true);
      } catch (BadItemException | ItemNotFoundException e) {
        logger.error("Block header hash does not exist or is bad: {}.",
            getDynamicPropertiesStore().getLatestBlockHeaderHash());
      }
    }
  }

  private void postBlockFilter(final BlockCapsule blockCapsule, boolean solidified) {
    BlockFilterCapsule blockFilterCapsule = new BlockFilterCapsule(blockCapsule, solidified);
    if (!filterCapsuleQueue.offer(blockFilterCapsule)) {
      logger.info("Too many filters, block filter lost: {}.", blockCapsule.getBlockId());
    }
  }

  private void postLogsFilter(final BlockCapsule blockCapsule, boolean solidified,
      boolean removed) {
    if (!blockCapsule.getTransactions().isEmpty()) {
      long blockNumber = blockCapsule.getNum();
      List<TransactionInfo> transactionInfoList = new ArrayList<>();

      try {
        TransactionRetCapsule result = chainBaseManager.getTransactionRetStore()
            .getTransactionInfoByBlockNum(ByteArray.fromLong(blockNumber));

        if (!Objects.isNull(result) && !Objects.isNull(result.getInstance())) {
          transactionInfoList.addAll(result.getInstance().getTransactioninfoList());
        }
      } catch (BadItemException e) {
        logger.error("ProcessLogsFilter getTransactionInfoList blockNum = {}, error is {}.",
            blockNumber, e.getMessage());
        return;
      }

      LogsFilterCapsule logsFilterCapsule = new LogsFilterCapsule(blockNumber,
          blockCapsule.getBlockId().toString(), blockCapsule.getBloom(), transactionInfoList,
          solidified, removed);

      if (!filterCapsuleQueue.offer(logsFilterCapsule)) {
        logger.info("Too many filters, logs filter lost: {}.", blockNumber);
      }
    }
  }

  private void postBlockTrigger(final BlockCapsule blockCapsule) {
    // post block and logs for jsonrpc
    if (CommonParameter.getInstance().isJsonRpcHttpFullNodeEnable()) {
      postBlockFilter(blockCapsule, false);
      postLogsFilter(blockCapsule, false, false);
    }

    // process block trigger
    long solidityBlkNum = getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
    if (eventPluginLoaded && EventPluginLoader.getInstance().isBlockLogTriggerEnable()) {
      List<BlockCapsule> capsuleList = new ArrayList<>();
      if (EventPluginLoader.getInstance().isBlockLogTriggerSolidified()) {
        capsuleList = getContinuousBlockCapsule(solidityBlkNum);
      } else {
        capsuleList.add(blockCapsule);
      }

      for (BlockCapsule capsule : capsuleList) {
        BlockLogTriggerCapsule blockLogTriggerCapsule = new BlockLogTriggerCapsule(capsule);
        blockLogTriggerCapsule.setLatestSolidifiedBlockNumber(solidityBlkNum);
        if (!triggerCapsuleQueue.offer(blockLogTriggerCapsule)) {
          logger.info("Too many triggers, block trigger lost: {}.", capsule.getBlockId());
        }
      }
    }

    // process transaction trigger
    if (eventPluginLoaded && EventPluginLoader.getInstance().isTransactionLogTriggerEnable()) {
      List<BlockCapsule> capsuleList = new ArrayList<>();
      if (EventPluginLoader.getInstance().isTransactionLogTriggerSolidified()) {
        capsuleList = getContinuousBlockCapsule(solidityBlkNum);
      } else {
        // need to reset block
        capsuleList.add(blockCapsule);
      }

      for (BlockCapsule capsule : capsuleList) {
        processTransactionTrigger(capsule);
      }
    }
  }

  private List<BlockCapsule> getContinuousBlockCapsule(long solidityBlkNum) {
    List<BlockCapsule> capsuleList = new ArrayList<>();
    long start = lastUsedSolidityNum < 0 ? solidityBlkNum : (lastUsedSolidityNum + 1);
    if (solidityBlkNum > start) {
      logger.info("Continuous block start:{}, end:{}", start, solidityBlkNum);
    }
    for (long blockNum = start; blockNum <= solidityBlkNum; blockNum++) {
      try {
        BlockCapsule capsule = chainBaseManager.getBlockByNum(blockNum);
        capsuleList.add(capsule);
      } catch (Exception e) {
        logger.error("GetContinuousBlockCapsule getBlockByNum blkNum = {} except, error is {}.",
            solidityBlkNum, e.getMessage());
      }
    }
    return capsuleList;
  }

  // return energyUsageTotal of the current transaction
  // cumulativeEnergyUsed is the total of energy used before the current transaction
  private long postTransactionTrigger(final TransactionCapsule trxCap,
      final BlockCapsule blockCap, int index, long preCumulativeEnergyUsed,
      long cumulativeLogCount, final TransactionInfo transactionInfo, long energyUnitPrice) {
    TransactionLogTriggerCapsule trx = new TransactionLogTriggerCapsule(trxCap, blockCap,
        index, preCumulativeEnergyUsed, cumulativeLogCount, transactionInfo, energyUnitPrice);
    trx.setLatestSolidifiedBlockNumber(getDynamicPropertiesStore()
        .getLatestSolidifiedBlockNum());
    if (!triggerCapsuleQueue.offer(trx)) {
      logger.info("Too many triggers, transaction trigger lost: {}.", trxCap.getTransactionId());
    }

    return trx.getTransactionLogTrigger().getEnergyUsageTotal();
  }


  private void postTransactionTrigger(final TransactionCapsule trxCap,
      final BlockCapsule blockCap) {
    TransactionLogTriggerCapsule trx = new TransactionLogTriggerCapsule(trxCap, blockCap);
    trx.setLatestSolidifiedBlockNumber(getDynamicPropertiesStore()
        .getLatestSolidifiedBlockNum());
    if (!triggerCapsuleQueue.offer(trx)) {
      logger.info("Too many triggers, transaction trigger lost: {}.", trxCap.getTransactionId());
    }
  }

  private void reOrgContractTrigger() {
    if (eventPluginLoaded
        && (EventPluginLoader.getInstance().isContractEventTriggerEnable()
        || EventPluginLoader.getInstance().isContractLogTriggerEnable())) {
      logger.info("Switch fork occurred, post reOrgContractTrigger.");
      try {
        BlockCapsule oldHeadBlock = chainBaseManager.getBlockById(
            getDynamicPropertiesStore().getLatestBlockHeaderHash());
        for (TransactionCapsule trx : oldHeadBlock.getTransactions()) {
          postContractTrigger(trx.getTrxTrace(), true, oldHeadBlock.getBlockId().toString());
        }
      } catch (BadItemException | ItemNotFoundException e) {
        logger.error("Block header hash does not exist or is bad: {}.",
            getDynamicPropertiesStore().getLatestBlockHeaderHash());
      }
    }
  }

  private void postContractTrigger(final TransactionTrace trace, boolean remove, String blockHash) {
    boolean isContractTriggerEnable = EventPluginLoader.getInstance()
        .isContractEventTriggerEnable() || EventPluginLoader
        .getInstance().isContractLogTriggerEnable();
    boolean isSolidityContractTriggerEnable = EventPluginLoader.getInstance()
        .isSolidityEventTriggerEnable() || EventPluginLoader
        .getInstance().isSolidityLogTriggerEnable();
    if (eventPluginLoaded
        && (isContractTriggerEnable || isSolidityContractTriggerEnable)) {
      // be careful, trace.getRuntimeResult().getTriggerList() should never return null
      for (ContractTrigger trigger : trace.getRuntimeResult().getTriggerList()) {
        ContractTriggerCapsule contractTriggerCapsule = new ContractTriggerCapsule(trigger);
        contractTriggerCapsule.getContractTrigger().setRemoved(remove);
        contractTriggerCapsule.setLatestSolidifiedBlockNumber(getDynamicPropertiesStore()
            .getLatestSolidifiedBlockNum());
        contractTriggerCapsule.setBlockHash(blockHash);

        if (!triggerCapsuleQueue.offer(contractTriggerCapsule)) {
          logger.info("Too many triggers, contract log trigger lost: {}.",
              trigger.getTransactionId());
        }
      }
    }
  }

  private void prepareStoreFactory() {
    StoreFactory.init();
    StoreFactory.getInstance().setChainBaseManager(chainBaseManager);
  }

  public TransactionCapsule getTxFromPending(String txId) {
    AtomicReference<TransactionCapsule> transactionCapsule = new AtomicReference<>();
    Sha256Hash txHash = Sha256Hash.wrap(ByteArray.fromHexString(txId));
    pendingTransactions.forEach(tx -> {
      if (tx.getTransactionId().equals(txHash)) {
        transactionCapsule.set(tx);
        return;
      }
    });
    if (transactionCapsule.get() != null) {
      return transactionCapsule.get();
    }
    rePushTransactions.forEach(tx -> {
      if (tx.getTransactionId().equals(txHash)) {
        transactionCapsule.set(tx);
        return;
      }
    });
    return transactionCapsule.get();
  }

  public Collection<String> getTxListFromPending() {
    Set<String> result = new HashSet<>();
    pendingTransactions.forEach(tx -> {
      result.add(tx.getTransactionId().toString());
    });
    rePushTransactions.forEach(tx -> {
      result.add(tx.getTransactionId().toString());
    });
    return result;
  }

  public long getPendingSize() {
    long value = getPendingTransactions().size() + getRePushTransactions().size()
        + getPoppedTransactions().size();
    return value;
  }

  private void initLiteNode() {
    // When using bloom filter for transaction de-duplication,
    // it is possible to use trans for secondary confirmation.
    // Init trans db for liteNode if needed.
    long headNum = chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    long recentBlockCount = chainBaseManager.getRecentBlockStore().size();
    long recentBlockStart = headNum - recentBlockCount + 1;
    boolean needInit = false;
    if (recentBlockStart == 0) {
      needInit = true;
    } else {
      try {
        chainBaseManager.getBlockByNum(recentBlockStart);
      } catch (ItemNotFoundException | BadItemException e) {
        needInit = true;
      }
    }

    if (needInit) {
      // copy transaction from recent-transaction to trans
      logger.info("Load trans for lite node.");

      TransactionCapsule item = new TransactionCapsule(Transaction.newBuilder().build());

      long transactionCount = 0;
      long minBlock = Long.MAX_VALUE;
      long maxBlock = Long.MIN_VALUE;
      for (Map.Entry<byte[], BytesCapsule> entry :
          chainBaseManager.getRecentTransactionStore()) {
        byte[] data = entry.getValue().getData();
        RecentTransactionItem trx =
            JsonUtil.json2Obj(new String(data), RecentTransactionItem.class);
        if (trx == null) {
          continue;
        }
        transactionCount += trx.getTransactionIds().size();
        long blockNum = trx.getNum();
        maxBlock = Math.max(maxBlock, blockNum);
        minBlock = Math.min(minBlock, blockNum);
        item.setBlockNum(blockNum);
        trx.getTransactionIds().forEach(
            tid -> chainBaseManager.getTransactionStore().put(Hex.decode(tid), item));
      }
      logger.info("Load trans complete, trans: {}, from = {}, to = {}.",
          transactionCount, minBlock, maxBlock);
    }
  }


  public void setBlockWaitLock(boolean waitFlag) {
    if (waitFlag) {
      blockWaitLock.incrementAndGet();
    } else {
      blockWaitLock.decrementAndGet();
    }
  }

  private boolean isBlockWaitingLock() {
    return blockWaitLock.get() > NO_BLOCK_WAITING_LOCK;
  }

  private static class ValidateSignTask implements Callable<Boolean> {

    private TransactionCapsule trx;
    private CountDownLatch countDownLatch;
    private ChainBaseManager manager;

    ValidateSignTask(TransactionCapsule trx, CountDownLatch countDownLatch,
        ChainBaseManager manager) {
      this.trx = trx;
      this.countDownLatch = countDownLatch;
      this.manager = manager;
    }

    @Override
    public Boolean call() throws ValidateSignatureException {
      try {
        trx.validateSignature(manager.getAccountStore(), manager.getDynamicPropertiesStore());
      } catch (ValidateSignatureException e) {
        throw e;
      } finally {
        countDownLatch.countDown();
      }
      return true;
    }
  }
}
