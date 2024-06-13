package org.tron.core.services.http;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

@Component
@Slf4j(topic = "API")
public class GetAddressAndTxServlet extends RateLimiterServlet {

  @Autowired DynamicPropertiesStore dynamicPropertiesStore;

  @Autowired ContractStateStore contractStateStore;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      Long startCycle =
          request.getParameter("start_cycle") == null
              ? dynamicPropertiesStore.getCurrentCycleNumber() - 28
              : Long.parseLong(request.getParameter("start_cycle"));
      long days =
          request.getParameter("days") == null ? 0 : Long.parseLong(request.getParameter("days"));
      if (days > 0) {
        StringBuilder sb = new StringBuilder("\"data\": [");
        for (int i = 0; i < days; i++) {
          long curStartCycle = startCycle + i * 4L;
          ContractStateCapsule dayCap = new ContractStateCapsule(curStartCycle);
          Map<String, Long> newAddressCountMap = new HashMap<>();
          for (long cycle = curStartCycle; cycle < curStartCycle + 4; cycle++) {
            ContractStateCapsule cycleCap = contractStateStore.getAddrAndTxRecord(cycle);
            if (cycleCap == null) {
              continue;
            }
            dayCap.addNewTransactionCount(cycleCap.getNewTransactionCount());
            dayCap.addNewUsdtOwner(cycleCap.getNewUsdtOwner());
            dayCap.addNewUsdtSender(cycleCap.getNewUsdtSender());
            cycleCap
                .getNewAddressCountMap()
                .forEach(
                    (key, value) ->
                        newAddressCountMap.put(
                            key, newAddressCountMap.getOrDefault(key, 0L) + value));

            if (cycle == curStartCycle + 3) {
              dayCap.setAddressDbSize(cycleCap.getAddressDbSize());
              dayCap.setTransactionDbSize(cycleCap.getTransactionDbSize());
            }
          }
          dayCap.setNewAddressCountMap(newAddressCountMap);
          sb.append(dayCap.toJsonString());
          if (i != days - 1) {
            sb.append(",");
          }
        }
        sb.append("]");
        response.getWriter().println(sb);
      } else {
        long cycleCount =
            request.getParameter("cycle_count") == null
                ? 28
                : Long.parseLong(request.getParameter("cycle_count"));
        ContractStateCapsule result =
            new ContractStateCapsule(dynamicPropertiesStore.getCurrentCycleNumber());
        ContractStateCapsule firstCap = contractStateStore.getAddrAndTxRecord(startCycle - 1);
        if (firstCap == null) {
          response.getWriter().println("Empty data");
          return;
        }

        Map<String, Long> newAddressCountMap = new HashMap<>();
        for (long cycle = startCycle; cycle < startCycle + cycleCount; cycle++) {
          ContractStateCapsule cycleCap = contractStateStore.getAddrAndTxRecord(cycle);
          if (cycleCap == null) {
            continue;
          }
          result.addNewTransactionCount(cycleCap.getNewTransactionCount());
          result.addNewUsdtOwner(cycleCap.getNewUsdtOwner());
          result.addNewUsdtSender(cycleCap.getNewUsdtSender());
          cycleCap
              .getNewAddressCountMap()
              .forEach(
                  (key, value) ->
                      newAddressCountMap.put(
                          key, newAddressCountMap.getOrDefault(key, 0L) + value));

          if (cycle == startCycle + cycleCount - 1) {
            result.setAddressDbSize(cycleCap.getAddressDbSize() - firstCap.getAddressDbSize());
            result.setTransactionDbSize(
                cycleCap.getTransactionDbSize() - firstCap.getTransactionDbSize());
          }
        }
        result.setNewAddressCountMap(newAddressCountMap);

        response.getWriter().println(result.toJsonString());
      }
    } catch (Exception e) {
      response.getWriter().println(e.getMessage());
    }
  }
}
