package org.tron.common.logsfilter.capsule;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import org.tron.common.logsfilter.EventPluginLoader;
import org.tron.common.logsfilter.TRC20Utils;
import org.tron.common.logsfilter.trigger.JustlendTrackerTrigger;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.TransactionTrace;

/**
 * === JustLend Feature ===
 */
@Slf4j
public class JustlendTrackerCapsule extends TriggerCapsule {

  @Getter
  @Setter
  JustlendTrackerTrigger justlendTrackerTrigger;

  public JustlendTrackerCapsule(BlockCapsule block) {
    justlendTrackerTrigger = new JustlendTrackerTrigger();
    justlendTrackerTrigger.setBlockHash(block.getBlockId().toString());
    justlendTrackerTrigger.setParentHash(block.getParentHash().toString());
    justlendTrackerTrigger.setBlockNumber(block.getNum());
    justlendTrackerTrigger.setTimeStamp(block.getTimeStamp());
    justlendTrackerTrigger.setSolidity(true);

    List<LogInfo> logInfos = new ArrayList<>();
    block.getTransactions().stream()
        .map(transactionCapsule -> transactionCapsule.getTrxTrace().getTransactionContext().getProgramResult().getLogInfoList())
        .filter(list -> !CollectionUtils.isEmpty(list))
        .forEach(logInfos::addAll);

    if (!CollectionUtils.isEmpty(logInfos)) {
      justlendTrackerTrigger.setAssetStatusList(buildAssetStatusList(block, logInfos));
    }

  }

  private List<JustlendTrackerTrigger.AssetStatusPojo> buildAssetStatusList(BlockCapsule blockCapsule, List<LogInfo> logInfos) {
    List<JustlendTrackerTrigger.AssetStatusPojo> result = new ArrayList<>();

    for (LogInfo logInfo : logInfos) {
      List<String> topics = logInfo.getHexTopics();
      if (CollectionUtils.isEmpty(topics) || StringUtils.isEmpty(logInfo.getHexData())) {
        continue;
      }

      String tokenAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getAddress()));
      boolean isRentMarket = EventPluginLoader.getInstance().getJustlendRentMarket().equals(tokenAddress)? true : false;
      if (StringUtils.isEmpty(tokenAddress) || (!EventPluginLoader.getInstance().getJustlendTokens().contains(tokenAddress) &&  !isRentMarket)) {
        continue;
      }


      switch (JustlendTrackerTrigger.HexTopicEnum.getBySignHash(topics.get(0))) {
        // SnapShot(address indexed user, uint256 amount);
        case SNAPSHOT:
          if (isRentMarket){
            continue;
          }
          if (topics.size() < 2) {
            continue;
          }

          String accountAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(1).getLast20Bytes()));
          BigInteger balance = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 0).toHexString());

          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.DEPOSIT.getType(), accountAddress, tokenAddress, balance, null, null);
          break;
        case TRANSFER:
          if (isRentMarket){
            continue;
          }
          if (topics.size() < 3) {
            continue;
          }

          String fromAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(1).getLast20Bytes()));
          String toAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(2).getLast20Bytes()));

          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.DEPOSIT.getType(), fromAddress, tokenAddress, TRC20Utils.getTRC20Balance(fromAddress, tokenAddress, blockCapsule), null, null);
          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.DEPOSIT.getType(),  toAddress, tokenAddress, TRC20Utils.getTRC20Balance(toAddress, tokenAddress, blockCapsule), null, null);
          break;
        case BORROW:
          if (isRentMarket){
            continue;
          }
          //event Borrow(address borrower, uint borrowAmount, uint accountBorrows, uint totalBorrows, uint borrowIndex);
          String borrower = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 0).getLast20Bytes()));
          BigInteger accountBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 2).toHexString());
          BigInteger totalBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 3).toHexString());

          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.BORROW.getType(),  borrower, tokenAddress, null, accountBorrows, totalBorrows);
          break;
        case REPAY_BORROW:
          if (isRentMarket){
            continue;
          }
          //event RepayBorrow(address payer, address borrower, uint repayAmount, uint accountBorrows, uint totalBorrows, uint borrowIndex);
          String borrowerAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 1).getLast20Bytes()));
          BigInteger accountTotalBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 3).toHexString());
          BigInteger marketTotalBorrows = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 4).toHexString());

          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.BORROW.getType(),  borrowerAddress, tokenAddress, null, accountTotalBorrows, marketTotalBorrows);
          break;
        case RENT_RESOURCE:
          if (!isRentMarket){
            continue;
          }
          //event RentResource(address indexed renter, address indexed receiver, uint256 addedAmount, uint256 resourceType, uint256 addedSecurityDeposit, uint256 amount, uint256 securityDeposit,uint256 rentIndex)
          String rentRentAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(1).getLast20Bytes()));
          String rentReceiverAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(2).getLast20Bytes()));
          BigInteger rentResourceType = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 1).toHexString());
          BigInteger rentAmount = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 3).toHexString());
          String rentOrder = JustlendTrackerTrigger.contactOrder(rentRentAddress, rentReceiverAddress, rentResourceType.toString());
          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.DEPOSIT.getType(), rentOrder, tokenAddress, rentAmount, null, null);
          break;
        case RETURN_RESOURCE:
          if (!isRentMarket){
            continue;
          }
          //event ReturnResource(address indexed renter,address indexed receiver,uint256 subedAmount,uint256 resourceType,uint256 usageRental,uint256 subedSecurityDeposit,uint256 amount,uint256 securityDeposit,uint256 rentIndex);
          String returnRentAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(1).getLast20Bytes()));
          String returnReceiverAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(2).getLast20Bytes()));
          BigInteger returnResourceType = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 1).toHexString());
          BigInteger returnAmount = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 4).toHexString());
          String returnOrder = JustlendTrackerTrigger.contactOrder(returnRentAddress, returnReceiverAddress, returnResourceType.toString());
          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.DEPOSIT.getType(), returnOrder, tokenAddress, returnAmount, null, null);
          break;
        case RENT_LIQUIDATE:
          if (!isRentMarket){
            continue;
          }
          //event Liquidate(address indexed liquidator,address indexed renter,address indexed receiver,uint256 amount,uint256 resourceType,uint256 usageRental,uint256 liquidateFee,uint256 sendBack);
          String liquidateRentAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(2).getLast20Bytes()));
          String liquidateReceiverAddress = StringUtil.encode58Check(TransactionTrace.convertToTronAddress(logInfo.getTopics().get(3).getLast20Bytes()));
          BigInteger liquidateResourceType = TRC20Utils.hexStrToBigInteger(DataWord.getDataWord(ByteArray.fromHexString(logInfo.getHexData()), 1).toHexString());
          String liquidateOrder = JustlendTrackerTrigger.contactOrder(liquidateRentAddress, liquidateReceiverAddress, liquidateResourceType.toString());
          addAssetStatusPojo(result, JustlendTrackerTrigger.MiningTypeEnum.DEPOSIT.getType(), liquidateOrder, tokenAddress, new BigInteger("0"), null, null);
          break;
      }

    }

    return result;
  }

  private void addAssetStatusPojo(List<JustlendTrackerTrigger.AssetStatusPojo> result,
                                  Integer miningType,
                                  String account,
                                  String token,
                                  BigInteger balance,
                                  BigInteger accountTotalBorrow,
                                  BigInteger marketTotalBorrow) {
    JustlendTrackerTrigger.AssetStatusPojo assetStatusPojo = new JustlendTrackerTrigger.AssetStatusPojo();
    assetStatusPojo.setMiningType(Objects.isNull(JustlendTrackerTrigger.MiningTypeEnum.getByType(miningType)) ? JustlendTrackerTrigger.MiningTypeEnum.UNKNOWN.getType() : miningType);
    assetStatusPojo.setAccountAddress(account);
    assetStatusPojo.setTokenAddress(token);
    assetStatusPojo.setBalance(Objects.nonNull(balance) ? balance.toString() : StringUtils.EMPTY);
    assetStatusPojo.setAccountTotalBorrow(Objects.nonNull(accountTotalBorrow) ? accountTotalBorrow.toString() : StringUtils.EMPTY);
    assetStatusPojo.setMarketTotalBorrow(Objects.nonNull(marketTotalBorrow) ? marketTotalBorrow.toString() : StringUtils.EMPTY);
    result.add(assetStatusPojo);
  }

  @Override
  public void processTrigger() {
    EventPluginLoader.getInstance().postJustlendTrackerTrigger(justlendTrackerTrigger);
  }

}
