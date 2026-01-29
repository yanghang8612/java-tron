package org.tron.common.logsfilter;

import org.pf4j.ExtensionPoint;

public interface IPluginEventListener extends ExtensionPoint {

  void setServerAddress(String address);

  void setTopic(int eventType, String topic);

  void setDBConfig(String dbConfig);

  // start should be called after setServerAddress, setTopic, setDBConfig
  void start();

  int getPendingSize();

  void handleBlockEvent(Object trigger);

  void handleTransactionTrigger(Object trigger);

  void handleContractLogTrigger(Object trigger);

  void handleContractEventTrigger(Object trigger);

  void handleSolidityTrigger(Object trigger);

  void handleSolidityLogTrigger(Object trigger);

  void handleSolidityEventTrigger(Object trigger);

  // === TronLink Feature ===
  void handleTRC20Event(Object trigger);

  // === TronLink Feature ===
  void handleFreezeBalanceEvent(Object trigger);

  // === TronLink Feature ===
  void handleStakeBalanceEvent(Object trigger);

  // === TronLink Feature ===
  void handleShieldedTRC20Event(Object trigger);

  // === TronLink Feature ===
  void handleTransferEvent(Object trigger);

  // === TronLink Feature ===
  void handleMultiAuthTrigger(Object data);

  // === JustLend Feature ===
  void handleJustLendTrackerTrigger(Object data);
}
