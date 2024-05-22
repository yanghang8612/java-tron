package org.tron.core.services.http;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
      Long cycleCount =
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
                    newAddressCountMap.put(key, newAddressCountMap.getOrDefault(key, 0L) + value));

        if (cycle == startCycle + cycleCount - 1) {
          result.setAddressDbSize(cycleCap.getAddressDbSize() - firstCap.getAddressDbSize());
          result.setTransactionDbSize(
              cycleCap.getTransactionDbSize() - firstCap.getTransactionDbSize());
        }
      }
      result.setNewAddressCountMap(newAddressCountMap);

      response.getWriter().println(result);
    } catch (Exception e) {
      response.getWriter().println(e.getMessage());
    }
  }
}
