package org.tron.common.logsfilter;

import com.beust.jcommander.internal.Sets;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.pf4j.CompoundPluginDescriptorFinder;
import org.pf4j.DefaultPluginManager;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.tron.common.logsfilter.trigger.MultiAuthTrackerTrigger;
import org.tron.common.logsfilter.trigger.TransferTrackerTrigger;
import org.tron.common.logsfilter.nativequeue.NativeMessageQueue;
import org.tron.common.logsfilter.trigger.BalanceTrackerTrigger;
import org.tron.common.logsfilter.trigger.BlockLogTrigger;
import org.tron.common.logsfilter.trigger.ContractEventTrigger;
import org.tron.common.logsfilter.trigger.ContractLogTrigger;
import org.tron.common.logsfilter.trigger.ContractTrigger;
import org.tron.common.logsfilter.trigger.FreezeBalanceTrigger;
import org.tron.common.logsfilter.trigger.JustlendTrackerTrigger;
import org.tron.common.logsfilter.trigger.ShieldedTRC20TrackerTrigger;
import org.tron.common.logsfilter.trigger.SolidityTrigger;
import org.tron.common.logsfilter.trigger.StakeBalanceTrigger;
import org.tron.common.logsfilter.trigger.TransactionLogTrigger;
import org.tron.common.logsfilter.trigger.Trigger;
import org.tron.core.Constant;
import org.tron.core.exception.TronError;

@Slf4j(topic = "DB")
public class EventPluginLoader {

  private static EventPluginLoader instance;

  private long MAX_PENDING_SIZE = 50000;

  private PluginManager pluginManager = null;

  private List<IPluginEventListener> eventListeners;

  private ObjectMapper objectMapper = new ObjectMapper();

  private String serverAddress;

  private String dbConfig;

  private List<TriggerConfig> triggerConfigList;

  // === JustLend Feature ===
  private List<String> justlendTokens;

  // === JustLend Feature ===
  private String justlendRentMarket;

  private int version = 0;

  private long startSyncBlockNum = 0;

  private boolean blockLogTriggerEnable = false;

  private boolean blockLogTriggerSolidified = false;

  private boolean transactionLogTriggerEnable = false;

  private boolean transactionLogTriggerSolidified = false;

  private boolean transactionLogTriggerEthCompatible = false;

  private boolean contractEventTriggerEnable = false;

  private boolean contractLogTriggerEnable = false;

  private boolean contractLogTriggerRedundancy = false;

  private boolean solidityEventTriggerEnable = false;

  private boolean solidityLogTriggerEnable = false;

  private boolean solidityLogTriggerRedundancy = false;

  private boolean solidityTriggerEnable = false;

  // === TronLink Feature ===
  private boolean balanceTrackerTriggerEnable = false;

  // === TronLink Feature ===
  private boolean transferTrackerTriggerEnable = false;

  // === TronLink Feature ===
  private boolean freezeBalanceTriggerEnable = false;

  // === TronLink Feature ===
  private boolean stakeBalanceTriggerEnable = false;

  // === TronLink Feature ===
  private boolean multiAuthTriggerEnable = false;

  // === TronLink Feature ===
  private boolean shieldedTRC20TrackerTriggerEnable = false;

  // === TronLink Feature ===
  private boolean shieldedTRC20TrackerSolidityTriggerEnable = false;

  // === JustLend Feature ===
  private boolean justlendTrackerTriggerEnable = false;

  private FilterQuery filterQuery;

  @Getter
  private boolean useNativeQueue = false;

  public static EventPluginLoader getInstance() {
    if (Objects.isNull(instance)) {
      synchronized (EventPluginLoader.class) {
        if (Objects.isNull(instance)) {
          instance = new EventPluginLoader();
        }
      }
    }
    return instance;
  }

  public static boolean matchFilter(ContractTrigger trigger) {
    long blockNumber = trigger.getBlockNumber();

    FilterQuery filterQuery = EventPluginLoader.getInstance().getFilterQuery();
    if (Objects.isNull(filterQuery)) {
      return true;
    }

    long fromBlockNumber = filterQuery.getFromBlock();
    long toBlockNumber = filterQuery.getToBlock();

    boolean matched = false;
    if (fromBlockNumber == FilterQuery.LATEST_BLOCK_NUM
        || toBlockNumber == FilterQuery.EARLIEST_BLOCK_NUM) {
      logger.error("invalid filter: fromBlockNumber: {}, toBlockNumber: {}",
          fromBlockNumber, toBlockNumber);
      return false;
    }

    if (toBlockNumber == FilterQuery.LATEST_BLOCK_NUM) {
      if (fromBlockNumber == FilterQuery.EARLIEST_BLOCK_NUM) {
        matched = true;
      } else {
        if (blockNumber >= fromBlockNumber) {
          matched = true;
        }
      }
    } else {
      if (fromBlockNumber == FilterQuery.EARLIEST_BLOCK_NUM) {
        if (blockNumber <= toBlockNumber) {
          matched = true;
        }
      } else {
        if (blockNumber >= fromBlockNumber && blockNumber <= toBlockNumber) {
          matched = true;
        }
      }
    }

    if (!matched) {
      return false;
    }

    return filterContractAddress(trigger, filterQuery.getContractAddressList())
        && filterContractTopicList(trigger, filterQuery.getContractTopicList());
  }

  private static boolean filterContractAddress(ContractTrigger trigger, List<String> addressList) {
    addressList = addressList.stream().filter(item ->
            org.apache.commons.lang3.StringUtils.isNotEmpty(item))
        .collect(Collectors.toList());
    if (Objects.isNull(addressList) || addressList.isEmpty()) {
      return true;
    }

    String contractAddress = trigger.getContractAddress();
    if (Objects.isNull(contractAddress)) {
      return false;
    }

    for (String address : addressList) {
      if (contractAddress.equalsIgnoreCase(address)) {
        return true;
      }
    }
    return false;
  }

  private static boolean filterContractTopicList(ContractTrigger trigger, List<String> topList) {
    topList = topList.stream().filter(item -> org.apache.commons.lang3.StringUtils.isNotEmpty(item))
        .collect(Collectors.toList());
    if (Objects.isNull(topList) || topList.isEmpty()) {
      return true;
    }

    Set<String> hset = Sets.newHashSet();
    if (trigger instanceof ContractLogTrigger) {
      hset = ((ContractLogTrigger) trigger).getTopicList().stream().collect(Collectors.toSet());
    } else if (trigger instanceof ContractEventTrigger) {
      hset = new HashSet<>(((ContractEventTrigger) trigger).getTopicMap().values());
    } else if (trigger != null) {
      hset = trigger.getLogInfo().getClonedTopics()
          .stream().map(Hex::toHexString).collect(Collectors.toSet());
    }

    for (String top : topList) {
      if (hset.contains(top)) {
        return true;
      }
    }
    return false;
  }

  private boolean launchNativeQueue(EventPluginConfig config) {

    if (!NativeMessageQueue.getInstance()
        .start(config.getBindPort(), config.getSendQueueLength())) {
      return false;
    }

    if (Objects.isNull(triggerConfigList)) {
      logger.error("trigger config is null");
      return false;
    }

    // === JustLend Feature ===
    if (justlendTrackerTriggerEnable &&
        (CollectionUtils.isEmpty(config.getJustlendTokens()) || StringUtils.isEmpty(config.getJustlendRentMarket()))) {
      throw new TronError(
          String.format("Node type is JustLend, `%s` & `%s` must be configured",
              Constant.EVENT_SUBSCRIBE_JUSTLEND_TOKENS,
              Constant.EVENT_SUBSCRIBE_JUSTLEND_RENT_MARKET),
          TronError.ErrCode.EVENT_SUBSCRIBE_INIT);
    }

    triggerConfigList.forEach(triggerConfig -> {
      setSingleTriggerConfig(triggerConfig);
    });

    return true;
  }

  private boolean launchEventPlugin(EventPluginConfig config) {
    // parsing subscribe config from config.conf
    String pluginPath = config.getPluginPath();
    this.serverAddress = config.getServerAddress();
    this.dbConfig = config.getDbConfig();

    if (!startPlugin(pluginPath)) {
      logger.error("failed to load '{}'", pluginPath);
      return false;
    }

    setPluginConfig();

    // === JustLend Feature ===
    if (justlendTrackerTriggerEnable &&
        (CollectionUtils.isEmpty(config.getJustlendTokens()) || StringUtils.isEmpty(config.getJustlendRentMarket()))) {
      throw new TronError(
          String.format("Node type is JustLend, `%s` & `%s` must be configured",
              Constant.EVENT_SUBSCRIBE_JUSTLEND_TOKENS,
              Constant.EVENT_SUBSCRIBE_JUSTLEND_RENT_MARKET),
          TronError.ErrCode.EVENT_SUBSCRIBE_INIT);
    }
    this.justlendTokens = config.getJustlendTokens();
    this.justlendRentMarket = config.getJustlendRentMarket();

    if (Objects.nonNull(eventListeners)) {
      eventListeners.forEach(listener -> listener.start());
    }

    return true;
  }

  public boolean start(EventPluginConfig config) {

    if (Objects.isNull(config)) {
      return false;
    }

    this.version = config.getVersion();

    this.startSyncBlockNum = config.getStartSyncBlockNum();

    this.triggerConfigList = config.getTriggerConfigList();

    useNativeQueue = config.isUseNativeQueue();

    if (config.isUseNativeQueue()) {
      return launchNativeQueue(config);
    }

    return launchEventPlugin(config);
  }

  private void setPluginConfig() {

    if (Objects.isNull(eventListeners)) {
      return;
    }

    // set server address to plugin
    eventListeners.forEach(listener -> listener.setServerAddress(this.serverAddress));

    // set db config to plugin
    eventListeners.forEach(listener -> listener.setDBConfig(this.dbConfig));

    triggerConfigList.forEach(triggerConfig -> {
      setSingleTriggerConfig(triggerConfig);
    });
  }

  private void setSingleTriggerConfig(TriggerConfig triggerConfig) {
    if (EventPluginConfig.BLOCK_TRIGGER_NAME.equalsIgnoreCase(triggerConfig.getTriggerName())) {
      if (triggerConfig.isEnabled()) {
        blockLogTriggerEnable = true;
        if (triggerConfig.isSolidified()) {
          blockLogTriggerSolidified = true;
        }
      } else {
        blockLogTriggerEnable = false;
        blockLogTriggerSolidified = false;
      }

      if (!useNativeQueue) {
        setPluginTopic(Trigger.BLOCK_TRIGGER, triggerConfig.getTopic());
      }

    } else if (EventPluginConfig.TRANSACTION_TRIGGER_NAME
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      if (triggerConfig.isEnabled()) {
        transactionLogTriggerEnable = true;
        if (triggerConfig.isEthCompatible()) {
          transactionLogTriggerEthCompatible = true;
        }
        if (triggerConfig.isSolidified()) {
          transactionLogTriggerSolidified = true;
        }
      } else {
        transactionLogTriggerEnable = false;
        transactionLogTriggerEthCompatible = false;
        transactionLogTriggerSolidified = false;
      }

      if (!useNativeQueue) {
        setPluginTopic(Trigger.TRANSACTION_TRIGGER, triggerConfig.getTopic());
      }

    } else if (EventPluginConfig.CONTRACTEVENT_TRIGGER_NAME
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      if (triggerConfig.isEnabled()) {
        contractEventTriggerEnable = true;
      } else {
        contractEventTriggerEnable = false;
      }

      if (!useNativeQueue) {
        setPluginTopic(Trigger.CONTRACTEVENT_TRIGGER, triggerConfig.getTopic());
      }

    } else if (EventPluginConfig.CONTRACTLOG_TRIGGER_NAME
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      if (triggerConfig.isEnabled()) {
        contractLogTriggerEnable = true;
        if (triggerConfig.isRedundancy()) {
          contractLogTriggerRedundancy = true;
        }
      } else {
        contractLogTriggerEnable = false;
        contractLogTriggerRedundancy = false;
      }

      if (!useNativeQueue) {
        setPluginTopic(Trigger.CONTRACTLOG_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.SOLIDITY_TRIGGER_NAME
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      if (triggerConfig.isEnabled()) {
        solidityTriggerEnable = true;
      } else {
        solidityTriggerEnable = false;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.SOLIDITY_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.SOLIDITY_EVENT_NAME
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      if (triggerConfig.isEnabled()) {
        solidityEventTriggerEnable = true;
      } else {
        solidityEventTriggerEnable = false;
      }

      if (!useNativeQueue) {
        setPluginTopic(Trigger.SOLIDITY_EVENT_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.SOLIDITY_LOG_NAME
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      if (triggerConfig.isEnabled()) {
        solidityLogTriggerEnable = true;
        if (triggerConfig.isRedundancy()) {
          solidityLogTriggerRedundancy = true;
        }
      } else {
        solidityLogTriggerEnable = false;
        solidityLogTriggerRedundancy = false;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.SOLIDITY_LOG_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.BALANCE_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === TronLink Feature ===
      if (triggerConfig.isEnabled()) {
        balanceTrackerTriggerEnable = true;
      } else {
        balanceTrackerTriggerEnable = false;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.TRC20TRACKER_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.TRANSFER_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === TronLink Feature ===
      if (triggerConfig.isEnabled()) {
        transferTrackerTriggerEnable = true;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.TRANSFER_TRACKER_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.FREEZE_BALANCE_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === TronLink Feature ===
      if (triggerConfig.isEnabled()) {
        freezeBalanceTriggerEnable = true;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.FREEZE_TRACKER_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.STAKE_BALANCE_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === TronLink Feature ===
      if (triggerConfig.isEnabled()) {
        stakeBalanceTriggerEnable = true;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.STAKE_TRACKER_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.MULTIAUTH_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === TronLink Feature ===
      if (triggerConfig.isEnabled()) {
        multiAuthTriggerEnable = true;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.MULTIAUTH_TRACKER_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.SHIELDED_TRC20_SOLIDITY_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === TronLink Feature ===
      if (triggerConfig.isEnabled()) {
        shieldedTRC20TrackerSolidityTriggerEnable = true;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.SHIELDED_TRC20SOLIDITYTRACKER_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.SHIELDED_TRC20_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === TronLink Feature ===
      if (triggerConfig.isEnabled()) {
        shieldedTRC20TrackerTriggerEnable = true;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.SHIELDED_TRC20TRACKER_TRIGGER, triggerConfig.getTopic());
      }
    } else if (EventPluginConfig.JUSTLEND_TRACKER
        .equalsIgnoreCase(triggerConfig.getTriggerName())) {
      // === JustLend Feature ===
      if (triggerConfig.isEnabled()) {
        justlendTrackerTriggerEnable = true;
      }
      if (!useNativeQueue) {
        setPluginTopic(Trigger.JUSTLEND_TRACKER_TRIGGER, triggerConfig.getTopic());
      }
    }
  }

  public void postSolidityTrigger(SolidityTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener ->
          listener.handleSolidityTrigger(toJsonString(trigger)));
    }
  }

  // === JustLend Feature ===
  public synchronized List<String> getJustlendTokens() {
    return justlendTokens;
  }

  // === JustLend Feature ===
  public synchronized String getJustlendRentMarket() {
    return justlendRentMarket;
  }


  public synchronized int getVersion() {
    return version;
  }

  public synchronized long getStartSyncBlockNum() {
    return startSyncBlockNum;
  }

  public synchronized boolean isBlockLogTriggerEnable() {
    return blockLogTriggerEnable;
  }

  public synchronized boolean isBlockLogTriggerSolidified() {
    return blockLogTriggerSolidified;
  }

  public synchronized boolean isSolidityTriggerEnable() {
    return solidityTriggerEnable;
  }

  public synchronized boolean isSolidityEventTriggerEnable() {
    return solidityEventTriggerEnable;
  }

  public synchronized boolean isSolidityLogTriggerEnable() {
    return solidityLogTriggerEnable;
  }

  public synchronized boolean isSolidityLogTriggerRedundancy() {
    return solidityLogTriggerRedundancy;
  }

  public synchronized boolean isTransactionLogTriggerEnable() {
    return transactionLogTriggerEnable;
  }

  public synchronized boolean isTransactionLogTriggerEthCompatible() {
    return transactionLogTriggerEthCompatible;
  }

  public synchronized boolean isTransactionLogTriggerSolidified() {
    return transactionLogTriggerSolidified;
  }

  public synchronized boolean isContractEventTriggerEnable() {
    return contractEventTriggerEnable;
  }

  public synchronized boolean isContractLogTriggerEnable() {
    return contractLogTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isBalanceTrackerTriggerEnable() {
    return balanceTrackerTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isTransferTrackerTriggerEnable() {
    return transferTrackerTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isMultiAuthTriggerEnable() {
    return multiAuthTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isFreezeBalanceTriggerEnable() {
    return freezeBalanceTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isStakeBalanceTriggerEnable() {
    return stakeBalanceTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isShieldedTRC20TrackerSolidityTriggerEnable() {
    return shieldedTRC20TrackerSolidityTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isShieldedTRC20TrackerTriggerEnable() {
    return shieldedTRC20TrackerTriggerEnable;
  }

  // === TronLink Feature ===
  public synchronized boolean isContractLogTriggerRedundancy() {
    return contractLogTriggerRedundancy;
  }

  // === JustLend Feature ===
  public synchronized boolean isJustlendTrackerTriggerEnable() {
    return justlendTrackerTriggerEnable;
  }

  private void setPluginTopic(int eventType, String topic) {
    eventListeners.forEach(listener -> listener.setTopic(eventType, topic));
  }

  private boolean startPlugin(String path) {

    logger.info("start loading '{}'", path);

    File pluginPath = new File(path);
    if (!pluginPath.exists()) {
      logger.error("'{}' doesn't exist", path);
      return false;
    }

    if (Objects.isNull(pluginManager)) {

      pluginManager = new DefaultPluginManager(pluginPath.toPath()) {
        @Override
        protected CompoundPluginDescriptorFinder createPluginDescriptorFinder() {
          return new CompoundPluginDescriptorFinder()
              .add(new ManifestPluginDescriptorFinder());
        }
      };
    }

    String pluginId = pluginManager.loadPlugin(pluginPath.toPath());
    if (StringUtils.isEmpty(pluginId)) {
      logger.error("invalid pluginID");
      return false;
    }

    pluginManager.startPlugins();

    eventListeners = pluginManager.getExtensions(IPluginEventListener.class);

    if (Objects.isNull(eventListeners) || eventListeners.isEmpty()) {
      logger.error("No eventListener is registered");
      return false;
    }

    logger.info("'{}' loaded", path);

    return true;
  }

  public void stopPlugin() {
    if (Objects.nonNull(pluginManager)) {
      pluginManager.stopPlugins();
    }

    NativeMessageQueue.getInstance().stop();

    logger.info("eventPlugin stopped");
  }

  public void postBlockTrigger(BlockLogTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener ->
          listener.handleBlockEvent(toJsonString(trigger)));
    }
  }

  public void postSolidityLogTrigger(ContractLogTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener ->
          listener.handleSolidityLogTrigger(toJsonString(trigger)));
    }
  }

  public void postSolidityEventTrigger(ContractEventTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener ->
          listener.handleSolidityEventTrigger(toJsonString(trigger)));
    }
  }

  public void postTransactionTrigger(TransactionLogTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener -> listener.handleTransactionTrigger(toJsonString(trigger)));
    }
  }

  public void postContractLogTrigger(ContractLogTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener ->
          listener.handleContractLogTrigger(toJsonString(trigger)));
    }
  }

  public void postContractEventTrigger(ContractEventTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener ->
          listener.handleContractEventTrigger(toJsonString(trigger)));
    }
  }

  // === TronLink Feature ===
  public void postTRC20TrackerTrigger(BalanceTrackerTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      long start = System.currentTimeMillis();
      eventListeners.forEach(listener ->
          listener.handleTRC20Event(toJsonString(trigger)));
      logger.info("EventTrigger-1 postTRC20TrackerTrigger blockNum {}, " +
          "AssetStatus-size {}, Trc10Status-size {}, Trc1155-size {}, Trc721-size {}, TrxStatus-size {}, " +
          "total-size {}, " +
          "cost {}ms",
        trigger.getBlockNumber(),

        trigger.getAssetStatusList().size(),
        trigger.getTrc10StatusList().size(),
        trigger.getTrc1155InfoList().size(),
        trigger.getTrc721InfoList().size(),
        trigger.getTrxStatusList().size(),

        trigger.getAssetStatusList().size()
          + trigger.getTrc10StatusList().size()
          + trigger.getTrc1155InfoList().size()
          + trigger.getTrc721InfoList().size()
          + trigger.getTrxStatusList().size(),

        System.currentTimeMillis() - start);
    }
  }

  // === TronLink Feature ===
  public void postFreezeBalanceTrigger(FreezeBalanceTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      long start = System.currentTimeMillis();
      eventListeners.forEach(listener ->
          listener.handleFreezeBalanceEvent(toJsonString(trigger)));
      logger.info("EventTrigger-2 postFreezeBalanceTrigger blockNum {}, size {}, cost {}ms",
        trigger.getBlockNumber(),
        trigger.getFreezeList().size(),
        System.currentTimeMillis() - start);
    }
  }

  // === TronLink Feature ===
  public void postStakeBalanceTrigger(StakeBalanceTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      long start = System.currentTimeMillis();
      eventListeners.forEach(listener ->
          listener.handleStakeBalanceEvent(toJsonString(trigger)));
      logger.info("EventTrigger-3 postStakeBalanceTrigger blockNum {}, size {}, cost {}ms",
        trigger.getBlockNumber(),
        trigger.getStakeList().size(),
        System.currentTimeMillis() - start);
    }
  }

  // === TronLink Feature ===
  public void postShieldedTRC20TrackerTrigger(ShieldedTRC20TrackerTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      long start = System.currentTimeMillis();
      eventListeners.forEach(listener ->
          listener.handleShieldedTRC20Event(toJsonString(trigger)));
      logger.info("EventTrigger-4 postShieldedTRC20TrackerTrigger blockNum {}, size {}, cost {}ms",
        trigger.getBlockNumber(),
        trigger.getTransactionList().size(),
        System.currentTimeMillis() - start);
    }
  }

  // === TronLink Feature ===
  public void postTransferTrigger(TransferTrackerTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      long start = System.currentTimeMillis();
      eventListeners.forEach(listener ->
          listener.handleTransferEvent(toJsonString(trigger)));
      logger.info("EventTrigger-5 postTransferTrigger blockNum {}, " +
          "AssetStatus-size {}, Trc10Status-size {}, Trc1155-size {}, Trc721-size {}, TrxStatus-size {}, " +
          "Trc10Asset-size {}, Trc20Asset-size {}, Trc721Asset-size {}, TrcAsset-size {}, total-size {}, " +
          "cost {}ms",
        trigger.getBlockNumber(),

        trigger.getAssetStatusList().size(),
        trigger.getTrc10StatusList().size(),
        trigger.getTrc1155InfoList().size(),
        trigger.getTrc721InfoList().size(),
        trigger.getTrxStatusList().size(),

        trigger.getTrc10AssetTransferInfoList().size(),
        trigger.getTrc20AssetTransferInfoList().size(),
        trigger.getTrc721AssetTransferInfoList().size(),
        trigger.getTrxAssetTransferInfoList().size(),

        trigger.getAssetStatusList().size()
          + trigger.getTrc10StatusList().size()
          + trigger.getTrc1155InfoList().size()
          + trigger.getTrc721InfoList().size()
          + trigger.getTrxStatusList().size()
          + trigger.getTrc10AssetTransferInfoList().size()
          + trigger.getTrc20AssetTransferInfoList().size()
          + trigger.getTrc721AssetTransferInfoList().size()
          + trigger.getTrxAssetTransferInfoList().size(),

        System.currentTimeMillis() - start);
    }
  }

  // === TronLink Feature ===
  public void postMultiAuthTrigger(MultiAuthTrackerTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      long start = System.currentTimeMillis();
      eventListeners.forEach(listener ->
          listener.handleMultiAuthTrigger(toJsonString(trigger)));
      logger.info("EventTrigger-6 postMultiAuthTrigger blockNum {}, size {}, cost {}ms",
        trigger.getBlockNumber(),
        trigger.getAuthInfoList().size(),
        System.currentTimeMillis() - start);
    }
  }

  // === JustLend Feature ===
  public void postJustlendTrackerTrigger(JustlendTrackerTrigger trigger) {
    if (useNativeQueue) {
      NativeMessageQueue.getInstance()
          .publishTrigger(toJsonString(trigger), trigger.getTriggerName());
    } else {
      eventListeners.forEach(listener ->
          listener.handleJustLendTrackerTrigger(toJsonString(trigger)));
    }
  }

  public boolean isBusy() {
    if (useNativeQueue) {
      return false;
    }
    int queueSize = 0;
    if (eventListeners == null || eventListeners.isEmpty()) {
      // only occurs in mock test. TODO fix test
      return false;
    }
    for (IPluginEventListener listener : eventListeners) {
      try {
        queueSize += listener.getPendingSize();
      } catch (AbstractMethodError error) {
        break;
      }
    }
    return queueSize >= MAX_PENDING_SIZE;
  }

  private String toJsonString(Object data) {
    String jsonData = "";

    try {
      jsonData = objectMapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      logger.error("'{}'", e);
    }

    return jsonData;
  }

  public synchronized FilterQuery getFilterQuery() {
    return filterQuery;
  }

  public synchronized void setFilterQuery(FilterQuery filterQuery) {
    this.filterQuery = filterQuery;
  }
}
