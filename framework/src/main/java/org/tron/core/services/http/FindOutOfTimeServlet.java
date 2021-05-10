package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.crypto.Hash;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.store.StoreFactory;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.contract.SmartContractOuterClass;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class FindOutOfTimeServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    Repository repository = RepositoryImpl.createRoot(StoreFactory.getInstance());
    long endBlockNum = repository.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    int slots = Integer.parseInt(request.getParameter("slots"));
    int threads = Integer.parseInt(request.getParameter("threads"));
    boolean findUSDT = Integer.parseInt(request.getParameter("usdt")) == 1;
    boolean isHour = Integer.parseInt(request.getParameter("ishour")) == 1;
    int blocks = isHour ? 60 * 20 : 24 * 60 * 20;
    int[] slot = new int[threads];
    for (int i = 0, j = 0; i < slots; i++, j = (j + 1) % threads) {
      slot[j] += 1;
    }
    List<Thread> threadList = new ArrayList<>();
    for (int i = threads - 1; i >= 0; i--) {
      long firstBlockNum = isHour
          ? findFirstBlockNumOfHour(endBlockNum - (long) slot[i] * blocks, repository)
          : findFirstBlockNumOfDay(endBlockNum - (long) slot[i] * blocks, repository);
      threadList.add(new Thread(new TraversalTask(firstBlockNum, endBlockNum, findUSDT), "Traversal-" + i));
      endBlockNum = firstBlockNum - 1;
    }
    threadList.forEach(Thread::start);
  }

  private static long findFirstBlockNumOfDay(long start, Repository rep) {
    LocalDateTime dateTime = Instant.ofEpochMilli(rep.getBlockByNum(start).getTimeStamp())
        .atZone(ZoneOffset.ofHours(8)).toLocalDateTime();
    int flag = dateTime.getHour() >= 12 ? 1 : -1, i = 1;
    int day = dateTime.getDayOfYear();
    while (true) {
      int curDay = Instant.ofEpochMilli(rep.getBlockByNum(start + flag * i).getTimeStamp())
          .atZone(ZoneOffset.ofHours(8)).toLocalDateTime().getDayOfYear();
      if (day != curDay) break;
      else i += 1;
    }
    return start + flag * i + (flag == -1 ? 1 : 0);
  }

  private static long findFirstBlockNumOfHour(long start, Repository rep) {
    LocalDateTime dateTime = Instant.ofEpochMilli(rep.getBlockByNum(start).getTimeStamp())
        .atZone(ZoneOffset.ofHours(8)).toLocalDateTime();
    int flag = dateTime.getMinute() >= 30 ? 1 : -1, i = 1;
    int hour = dateTime.getHour();
    while (true) {
      int curHour = Instant.ofEpochMilli(rep.getBlockByNum(start + flag * i).getTimeStamp())
          .atZone(ZoneOffset.ofHours(8)).toLocalDateTime().getHour();
      if (hour != curHour) break;
      else i += 1;
    }
    return start + flag * i + (flag == -1 ? 1 : 0);
  }

  private static class Data {
    long outOfTime = 0;
    long txCnt = 0;

    @Override
    public String toString() {
      return outOfTime + " " + txCnt;
    }
  }

  private static class TraversalTask implements Runnable {

    private final long startBlockNum;

    private final long endBlockNum;

    private final boolean findUSDT;

    public TraversalTask(long startBlockNum, long endBlockNum, boolean findUSDT) {
      this.startBlockNum = startBlockNum;
      this.endBlockNum = endBlockNum;
      this.findUSDT = findUSDT;
      System.out.println(startBlockNum + ":" + endBlockNum);
    }

    @Override
    public void run() {
      Map<String, Data> map = new HashMap<>();
      Repository repository = RepositoryImpl.createRoot(StoreFactory.getInstance());
      byte[] selector = Hash.sha3("transfer(address,uint256)".getBytes());
      long txCnt = 0, outOfTime = 0, curTxCnt = 0, curOutOfTime = 0,
          startTime = System.currentTimeMillis(), curBlockNum = startBlockNum;
      for (int days = 0; curBlockNum <= endBlockNum; curBlockNum++) {
        BlockCapsule blockCapsule = repository.getBlockByNum(curBlockNum);
        LocalDateTime date = Instant.ofEpochMilli(blockCapsule.getTimeStamp())
            .atZone(ZoneOffset.ofHours(8)).toLocalDateTime();
        if (days == 0) days = date.getDayOfYear();
        if (date.getDayOfYear() != days) {
          txCnt += curTxCnt;
          outOfTime += curOutOfTime;
          System.out.println(Thread.currentThread().getName() + ": " + date.plusSeconds(6)
              + " " + curTxCnt + " " + curOutOfTime + " " + txCnt + " " + outOfTime
              + " " + (System.currentTimeMillis() - startTime) + "ms");
          try {
            for (String key : map.keySet()) {
              BufferedWriter bw = new BufferedWriter(new FileWriter(key, true));
              DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");
              bw.write(String.format("%s %d %d%n", df.format(date.plusDays(1)), map.get(key).outOfTime, map.get(key).txCnt));
              bw.flush();
              bw.close();
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
          curTxCnt = curOutOfTime = 0;
          startTime = System.currentTimeMillis();
          days = date.getDayOfYear();
          map = new HashMap<>();
        }
        String sr = StringUtil.encode58Check(blockCapsule.getWitnessAddress().toByteArray());
        List<TransactionCapsule> transactions = blockCapsule.getTransactions();
        for (TransactionCapsule cap : transactions) {
          Protocol.Transaction t = cap.getInstance();
          List<Protocol.Transaction.Contract> contracts = t.getRawData().getContractList();
          if (contracts.size() > 0 && contracts.get(0).getType()
              == Protocol.Transaction.Contract.ContractType.TriggerSmartContract) {
            SmartContractOuterClass.TriggerSmartContract contract;
            try {
              contract = contracts.get(0).getParameter().unpack(SmartContractOuterClass.TriggerSmartContract.class);
              if (!findUSDT || ("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t".equals(
                  StringUtil.encode58Check(contract.getContractAddress().toByteArray()))
                  && check(selector, contract.getData().toByteArray()))) {
                curTxCnt += 1;
                if (!map.containsKey(sr)) map.put(sr, new Data());
                map.get(sr).txCnt += 1;
                if (cap.getContractResult() == Protocol.Transaction.Result.contractResult.OUT_OF_TIME) {
                  curOutOfTime += 1;
                  map.get(sr).outOfTime += 1;
                  BufferedWriter bw = new BufferedWriter(new FileWriter("contract", true));
                  bw.write(String.format("%s %s%n",
                      StringUtil.encode58Check(contract.getContractAddress().toByteArray()),
                      cap.getTransactionId().toString()));
                  bw.flush();
                  bw.close();
                }
              }
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        }
      }
    }
  }

  private static boolean check(byte[] a, byte[] b) {
    if (b == null || b.length < 4) return false;
    for (int i = 0; i < 4; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
