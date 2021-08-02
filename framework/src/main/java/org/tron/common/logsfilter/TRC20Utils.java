package org.tron.common.logsfilter;

import com.google.common.collect.Lists;
import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.tron.common.entity.AssetTransferInfo;
import org.tron.common.entity.AssetTransferLogInfo;
import org.tron.common.logsfilter.trigger.BalanceTrackerTrigger;
import org.tron.common.logsfilter.trigger.BalanceTrackerTrigger.AssetStatusPojo;
import org.tron.common.logsfilter.trigger.BalanceTrackerTrigger.ConcernTopics;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.runtime.vm.LogInfo;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.actuator.VMActuator;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionContext;
import org.tron.core.db.TransactionTrace;
import org.tron.core.store.StoreFactory;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

@Slf4j
public class TRC20Utils {

  static VMActuator vmActuator = new VMActuator(true);
  static final String WTRXAddress = "TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR";


  public static BigInteger getTRC20Decimal(String contractAddress, BlockCapsule baseBlockCap) {
    byte[] data = Hex.decode("313ce567");
    ProgramResult result = triggerFromVM(contractAddress, data, baseBlockCap);
    if (Objects.isNull(result.getException()) && !result.isRevert() && StringUtils
        .isEmpty(result.getRuntimeError())
        && result.getHReturn() != null) {
      try {
        BigInteger ret = toBigInteger(result.getHReturn());
        return ret;
      } catch (Exception e) {
      }
    }

    logger.error(" >>>>> getTRC20Decimal get error, {}", contractAddress);
    return null;
  }

  public static String getTRC721Url(String contractAddress, String assetId, BlockCapsule baseBlockCap) {
    try {
      byte[] bytes = new BigInteger(assetId).toByteArray();

      if (bytes.length > 32) {
        bytes = Arrays.copyOfRange(bytes, 1, 33);
      }

      final DataWord dataWord = new DataWord(bytes);
      byte[] data = Bytes.concat(Hex.decode("c87b56dd"), dataWord.getData());
      ProgramResult result = triggerFromVM(contractAddress, data, baseBlockCap);
      if (Objects.isNull(result.getException()) && !result.isRevert() && StringUtils
          .isEmpty(result.getRuntimeError())
          && result.getHReturn() != null) {
        try {
          return unpackString(result.getHReturn());
        } catch (Exception e) {
          logger.error("", e);
        }
      }

      logger.error(" result.getRuntimeError:{}", result.getRuntimeError());
    } catch (Exception ex) {
      logger.error("", ex);
    }

    logger.error(" >>>>> getTRC721Url get error, {}", contractAddress);
    return "";
  }


  private static final int WORD_LENGTH = DataWord.WORD_SIZE;

  public static String unpackString(byte[] data) {
    if (data.length < 2 * WORD_LENGTH || data.length % WORD_LENGTH != 0) {
      return "";
    }
    int index = DataWord.getDataWord(data, 0).intValue();
    int valueLength = DataWord.getDataWord(data, index / WORD_LENGTH).intValue();
    if (valueLength > 0) {
      byte[] range = Arrays
              .copyOfRange(data, index + WORD_LENGTH, index + WORD_LENGTH + valueLength);
      return ByteArray.toStr(range);
    }
    return "";
  }

  public static BigInteger hexStrToBigInteger(String hexStr) {
    if (!StringUtils.isEmpty(hexStr)) {
      try {
        return new BigInteger(hexStr, 16);
      } catch (Exception e) {
      }
    }
    return null;
  }

  public static BigInteger toBigInteger(byte[] input) {
    if (input != null && input.length > 0) {
      try {
        if (input.length > 32) {
          input = Arrays.copyOfRange(input, 0, 32);
        }

        String hex = Hex.toHexString(input);
        return hexStrToBigInteger(hex);
      } catch (Exception e) {
      }
    }
    return null;
  }

  public static BigInteger getTRC20Balance(String ownerAddress, String contractAddress,
      BlockCapsule baseBlockCap) {
    // 70a08231 balanceOf(address)
    // 000000000000000000000041DDB2CC247E543F1462711989FCC89379F943B623
    byte[] data = Bytes.concat(Hex.decode("70a082310000000000000000000000"),
            Commons.decodeFromBase58Check(ownerAddress));
    ProgramResult result = triggerFromVM(contractAddress, data, baseBlockCap);
    if (Objects.isNull(result.getException()) &&
        !result.isRevert() && StringUtils.isEmpty(result.getRuntimeError())
        && result.getHReturn() != null) {
      try {
        BigInteger ret = toBigInteger(result.getHReturn());
        return ret;
      } catch (Exception e) {
      }
    }

    logger.error(" >>>>> getTRC20Balance get error, {}, ownerAddress:{}", contractAddress, ownerAddress);
    return null;

  }

  public static final String TRC20 = "trc20";
  public static final String TRC721 = "trc721";
  public static final String TRC20_TRANSFER = "trc20Transfer";
  public static final String TRC721_TRANSFER = "trc721Transfer";

  public static Map<String, Object> parseTrc20AssetStatusPojo(BlockCapsule block, List<LogInfo> logInfos) {
    Set<String> trc20Tokens = new HashSet<>();
    Map<String, BigInteger> trc20IncrementMap = new LinkedHashMap<>();
    Map<String, BigInteger> balanceMap = new LinkedHashMap<>();
    Map<String, BigInteger> decimalMap = new LinkedHashMap<>();
    Map<String, Map<String, List<BalanceTrackerTrigger.Trc721Info>>> trc721InfoMap = new HashMap<>();
    handlerLogs(trc20IncrementMap, logInfos, trc20Tokens, trc721InfoMap);
    if (!CollectionUtils.isEmpty(trc721InfoMap)) {
      logger.info(" >>>> trc721InfoMap:{}", trc721InfoMap);
    }

    final List<AssetStatusPojo> trc20AssetList = handlerTrc20Asset(block, trc20IncrementMap, balanceMap, decimalMap, trc20Tokens);
    final List<BalanceTrackerTrigger.Trc721Info> trc721Infos = handlerTrc721(trc721InfoMap, block);
    Map<String, Object> result = new HashMap<>();
    result.put(TRC20, trc20AssetList);
    result.put(TRC721, trc721Infos);
    if (!CollectionUtils.isEmpty(trc721Infos)) {
      logger.info(" >>>> trc721Infos:{}", trc721Infos);
    }
    return result;
  }

  public static Map<String, Object> parseTrc20Transfer(List<AssetTransferLogInfo> assetTransferLogInfos) {
    List<AssetTransferInfo> trc20AssetTransferInfoList = new ArrayList<>();
    List<AssetTransferInfo> trc721AssetTransferInfoList = new ArrayList<>();
    handlerTransferLogs(assetTransferLogInfos, trc20AssetTransferInfoList, trc721AssetTransferInfoList);

    Map<String, Object> result = new HashMap<>();
    result.put(TRC20_TRANSFER, trc20AssetTransferInfoList);
    result.put(TRC721_TRANSFER, trc721AssetTransferInfoList);

    if (!CollectionUtils.isEmpty(trc20AssetTransferInfoList)) {
      logger.info(" >>>> trc20AssetTransferList:{}", trc20AssetTransferInfoList);
    }

    if (!CollectionUtils.isEmpty(trc721AssetTransferInfoList)) {
      logger.info(" >>>> trc721AssetTransferList:{}", trc721AssetTransferInfoList);
    }

    return result;
  }

  private static List<BalanceTrackerTrigger.Trc721Info> handlerTrc721(Map<String, Map<String, List<BalanceTrackerTrigger.Trc721Info>>> trc721InfoMap,
                                                                      BlockCapsule block) {
    if (CollectionUtils.isEmpty(trc721InfoMap)) {
      return Lists.newArrayList();
    }

    List<BalanceTrackerTrigger.Trc721Info> result = new LinkedList<>();
    trc721InfoMap.forEach((tokenAddress, map) -> {
      map.forEach((assetId, list) -> {
        if (CollectionUtils.isEmpty(list)) {
          return;
        }

        BalanceTrackerTrigger.Trc721Info info = null;
        if (list.size() == 1) {
          info = list.get(0);
        } else {
          info = mergeTrc721(list);
          if (info == null) {
            return;
          }
        }

        triggerTrc721(info, block);
        result.add(info);
      });
    });

    return result;
  }

  private static void triggerTrc721(BalanceTrackerTrigger.Trc721Info info, BlockCapsule block) {
    info.setAssetUrl(getTRC721Url(info.getTokenAddress(), info.getAssetId(), block));
    info.setAssetUrlTime(System.currentTimeMillis());
  }

  private static BalanceTrackerTrigger.Trc721Info mergeTrc721(List<BalanceTrackerTrigger.Trc721Info> list) {
    Set<String> fromSet = new HashSet<>();
    Set<String> toSet = new HashSet<>();

    list.forEach(item -> {
      fromSet.add(item.getFromAccountAddress());
      toSet.add(item.getToAccountAddress());
    });

    final BalanceTrackerTrigger.Trc721Info info = list.get(0);
    final HashSet<String> copyFrom = new HashSet<>(fromSet);
    fromSet.removeAll(toSet);

    if (fromSet.size() == 1) {
      info.setFromAccountAddress(fromSet.iterator().next());
    } else {
      logger.error(" >>>> trc721 merge data error!!! {}", list);
      return null;
    }

    toSet.removeAll(copyFrom);

    if (toSet.size() == 1) {
      info.setToAccountAddress(toSet.iterator().next());
    } else {
      logger.error(" >>>> trc721 merge data error!!! {}", list);
      return null;
    }

    return info;
  }

  private static List<AssetStatusPojo> handlerTrc20Asset(BlockCapsule block, Map<String, BigInteger> trc20IncrementMap,
                                                         Map<String, BigInteger> balanceMap, Map<String, BigInteger> decimalMap,
                                                         Set<String> trc20Tokens) {
    for (String keys : trc20IncrementMap.keySet()) {
      // foreach address try to get it's balance.
      String[] key = keys.split(",");
      BigInteger balance = TRC20Utils.getTRC20Balance(key[0], key[1], block);
      if (balance != null) {
        balanceMap.put(keys, balance);
      }
    }

    for (String token : trc20Tokens) {
      BigInteger decimals = TRC20Utils.getTRC20Decimal(token, block);
      if (decimals != null) {
        decimalMap.put(token, decimals);
      }
    }

    CommonParameter.getInstance().setDebug(false);
    logger.debug("trc20IncrementMap: {}", trc20IncrementMap);
    logger.debug("balanceMap: {}", balanceMap);
    logger.debug("decimalsMap: {}", decimalMap);

    List<AssetStatusPojo> result = new LinkedList<>();
    for (String keys : trc20IncrementMap.keySet()) {
      String[] key = keys.split(",");
      AssetStatusPojo assetStatusPojo = new AssetStatusPojo();
      assetStatusPojo.setAccountAddress(key[0]);
      assetStatusPojo.setTokenAddress(key[1]);
      assetStatusPojo.setIncrementBalance(bigIntegertoString(trc20IncrementMap.get(keys)));
      assetStatusPojo.setBalance(bigIntegertoString(balanceMap.get(keys)));
      assetStatusPojo.setDecimals(bigIntegertoString(decimalMap.get(key[1])));
      result.add(assetStatusPojo);
    }

    return result;
  }

  private static void handlerLogs(Map<String, BigInteger> incrementMap, List<LogInfo> logInfos,
                                  Set<String> trc20Tokens,
                                  Map<String, Map<String, List<BalanceTrackerTrigger.Trc721Info>>> trc721InfoMap) {
    for (LogInfo logInfo : logInfos) {
      List<String> topics = logInfo.getHexTopics();
      if (CollectionUtils.isEmpty(topics)) {
        continue;
      }

      String tokenAddress = convertAddress(logInfo.getAddress());
      switch (ConcernTopics.getBySH(topics.get(0))) {
        case TRANSFER:
          if (topics.size() < 3) {
            continue;
          }

          //TransferCase : decrease sender, increase receiver
          String senderAddr = convertAddress(logInfo.getTopics().get(1).getLast20Bytes());
          String recAddr = convertAddress(logInfo.getTopics().get(2).getLast20Bytes());

          if (topics.size() == 3) {
            // 是trc20
            BigInteger increment = hexStrToBigInteger(logInfo.getHexData());
            if (increment == null) {
              continue;
            }

            adjustIncrement(incrementMap, senderAddr, tokenAddress, increment.negate());
            adjustIncrement(incrementMap, recAddr, tokenAddress, increment);
            trc20Tokens.add(tokenAddress);
          } else if(topics.size() == 4) {
            // 是trc721
            final byte[] data = logInfo.getTopics().get(3).getData();
            logger.info(" transfer, data: {} ", Arrays.toString(data));
            String assetId = new BigInteger(1, data).toString();
            logger.info(" transfer: {} , {}, {}, {}", tokenAddress, senderAddr, recAddr, assetId);
            handlerTrc721(assetId, tokenAddress, senderAddr, recAddr, trc721InfoMap);
          }

          break;
        case Deposit:
          if (!tokenAddress.equals(WTRXAddress) || topics.size() < 2) {
            continue;
          }
          // 是trc20
          BigInteger increment = hexStrToBigInteger(logInfo.getHexData());
          if (increment == null) {
            continue;
          }
          //DepositCase : increase receiver
          recAddr = convertAddress(logInfo.getTopics().get(1).getLast20Bytes());
          adjustIncrement(incrementMap, recAddr, tokenAddress, increment);
          trc20Tokens.add(tokenAddress);
          break;
        case Withdrawal:
          if (!tokenAddress.equals(WTRXAddress) || topics.size() < 2) {
            continue;
          }
          // 是trc20
          increment = hexStrToBigInteger(logInfo.getHexData());
          if (increment == null) {
            continue;
          }
          //WithdrawalCase : decrease sender
          senderAddr = convertAddress(logInfo.getTopics().get(1).getLast20Bytes());
          adjustIncrement(incrementMap, senderAddr, tokenAddress, increment.negate());
          trc20Tokens.add(tokenAddress);
          break;
        default:
          continue;
      }
    }
  }

  private static void handlerTransferLogs(List<AssetTransferLogInfo> assetTransferLogInfos,
                                  List<AssetTransferInfo> trc20AssetTransferInfoList,
                                  List<AssetTransferInfo> trc721AssetTransferInfoList) {
    for (AssetTransferLogInfo item : assetTransferLogInfos) {
      List<LogInfo> logInfoList = item.getLogInfoList();
      if (CollectionUtils.isEmpty(logInfoList)) {
        continue;
      }

      for (LogInfo logInfo : logInfoList) {
        List<String> topics = logInfo.getHexTopics();
        if (CollectionUtils.isEmpty(topics)) {
          continue;
        }

        String tokenAddress = convertAddress(logInfo.getAddress());

        AssetTransferInfo assetTransferInfo = new AssetTransferInfo();
        assetTransferInfo.setTokenAddress(tokenAddress);
        assetTransferInfo.setTxId(item.getTxId());
        assetTransferInfo.setNote(item.getNote());
        assetTransferInfo.setIsSuccess(item.getIsSuccess());

        switch (ConcernTopics.getBySH(topics.get(0))) {
          case TRANSFER:
            if (topics.size() < 3) {
              continue;
            }

            //TransferCase : decrease sender, increase receiver
            String senderAddr = convertAddress(logInfo.getTopics().get(1).getLast20Bytes());
            String recAddr = convertAddress(logInfo.getTopics().get(2).getLast20Bytes());

            assetTransferInfo.setFromAddress(senderAddr);
            assetTransferInfo.setToAddress(recAddr);

            if (topics.size() == 3) {
              // 是trc20
              BigInteger increment = hexStrToBigInteger(logInfo.getHexData());
              if (increment == null) {
                continue;
              }

              assetTransferInfo.setAssetType(2);
              assetTransferInfo.setAmount(String.valueOf(increment));
              trc20AssetTransferInfoList.add(assetTransferInfo);
            } else if(topics.size() == 4) {
              // 是trc721
              final byte[] data = logInfo.getTopics().get(3).getData();
              logger.info(" handlerTransferLogs trc721 data: {} ", Arrays.toString(data));
              String assetId = new BigInteger(1, data).toString();
              logger.info(" handlerTransferLogs trc721 {} , {}, {}, {}", tokenAddress, senderAddr, recAddr, assetId);

              assetTransferInfo.setAssetType(3);
              assetTransferInfo.setAmount("1");
              assetTransferInfo.setAssetId(assetId);
              trc721AssetTransferInfoList.add(assetTransferInfo);
            }

            break;
          case Deposit:
            if (!tokenAddress.equals(WTRXAddress) || topics.size() < 2) {
              continue;
            }
            // 是trc20
            BigInteger increment = hexStrToBigInteger(logInfo.getHexData());
            if (increment == null) {
              continue;
            }
            //DepositCase : increase receiver
            recAddr = convertAddress(logInfo.getTopics().get(1).getLast20Bytes());

            assetTransferInfo.setFromAddress("");
            assetTransferInfo.setToAddress(recAddr);
            assetTransferInfo.setTokenAddress(tokenAddress);
            assetTransferInfo.setAmount(String.valueOf(increment));
            assetTransferInfo.setAssetType(2);
            trc20AssetTransferInfoList.add(assetTransferInfo);
            break;
          case Withdrawal:
            if (!tokenAddress.equals(WTRXAddress) || topics.size() < 2) {
              continue;
            }
            // 是trc20
            increment = hexStrToBigInteger(logInfo.getHexData());
            if (increment == null) {
              continue;
            }
            //WithdrawalCase : decrease sender
            senderAddr = convertAddress(logInfo.getTopics().get(1).getLast20Bytes());

            assetTransferInfo.setFromAddress(senderAddr);
            assetTransferInfo.setToAddress("");
            assetTransferInfo.setTokenAddress(tokenAddress);
            assetTransferInfo.setAmount(String.valueOf(increment));
            assetTransferInfo.setAssetType(2);
            trc20AssetTransferInfoList.add(assetTransferInfo);
            break;
          default:
            continue;
        }
      }
    }
  }

  private static String convertAddress(byte[] data) {
    return StringUtil.encode58Check(TransactionTrace.convertToTronAddress(data));
  }

  private static void handlerTrc721(String assetId, String tokenAddress, String senderAddr, String recAddr,
                                    Map<String, Map<String, List<BalanceTrackerTrigger.Trc721Info>>> trc721InfoMap) {
    if (StringUtils.isEmpty(assetId)) {
      return;
    }

    Map<String, List<BalanceTrackerTrigger.Trc721Info>> tokenMap = trc721InfoMap.get(tokenAddress);
    if (tokenMap == null) {
      tokenMap = new HashMap<>();
      trc721InfoMap.put(tokenAddress, tokenMap);
    }

    List<BalanceTrackerTrigger.Trc721Info> trc721Infos = tokenMap.get(assetId);

    if (trc721Infos == null) {
      trc721Infos = new LinkedList<>();
      tokenMap.put(assetId, trc721Infos);
    }

    BalanceTrackerTrigger.Trc721Info trc721Info = new BalanceTrackerTrigger.Trc721Info();
    trc721Info.setTokenAddress(tokenAddress);
    trc721Info.setFromAccountAddress(senderAddr);
    trc721Info.setToAccountAddress(recAddr);
    trc721Info.setAssetId(assetId);
    trc721Infos.add(trc721Info);
  }

  private static String bigIntegertoString(BigInteger bigInteger) {
    if (bigInteger != null) {
      return bigInteger.toString();
    }

    return "0";
  }

  private static void adjustIncrement(Map<String, BigInteger> incrementMap, String address,
      String token,
      BigInteger wad) {
    BigInteger previous = incrementMap.get(address + "," + token);
    if (previous == null) {
      previous = new BigInteger("0");
    }
    previous = previous.add(wad);
    incrementMap.put(address + "," + token, previous);
  }


  private static ProgramResult triggerFromVM(String contractAddress, byte[] data,
      BlockCapsule baseBlockCap) {
    TriggerSmartContract.Builder build = TriggerSmartContract.newBuilder();
    build.setData(
        ByteString.copyFrom(data));
    build.setOwnerAddress(ByteString.EMPTY);
    build.setCallValue(0);
    build.setCallTokenValue(0);
    build.setTokenId(0);
    build.setContractAddress(ByteString.copyFrom(Commons.decodeFromBase58Check(contractAddress)));
    TransactionCapsule trx = new TransactionCapsule(build.build(),
        ContractType.TriggerSmartContract);
    Transaction.Builder txBuilder = trx.getInstance().toBuilder();
    Transaction.raw.Builder rawBuilder = trx.getInstance().getRawData().toBuilder();
    rawBuilder.setFeeLimit(1000000000L);
    txBuilder.setRawData(rawBuilder);

    TransactionContext context = new TransactionContext(baseBlockCap,
        new TransactionCapsule(txBuilder.build()),
        StoreFactory.getInstance(), true,
        false);

    try {
      vmActuator.validate(context);
      vmActuator.execute(context);
    } catch (Exception e) {
      logger.warn("{} trigger failed!", contractAddress);
    }

    ProgramResult result = context.getProgramResult();
    return result;
  }
}
