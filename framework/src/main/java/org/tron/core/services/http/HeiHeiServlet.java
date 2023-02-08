package org.tron.core.services.http;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

@Component
@Slf4j(topic = "API")
public class HeiHeiServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

      String address = request.getParameter("address");
      String cycleNumber = request.getParameter("cycle_number");
      if (cycleNumber == null) {
        cycleNumber = String.valueOf(dps.getCurrentCycleNumber());
      }
      response.getWriter().println("Current cycle number: " + dps.getCurrentCycleNumber());
      response.getWriter().println("Query cycle number: " + cycleNumber);

      if (address == null) {
        Map<WrappedByteArray, ContractStateCapsule> contracts =
            css.prefixQuery(cycleNumber.getBytes());
        List<Map.Entry<WrappedByteArray, ContractStateCapsule>> list =
            new LinkedList<>(contracts.entrySet());
        String sortedBy = request.getParameter("sorted_by");
        switch (sortedBy) {
          case "totalUsage":
            list.sort((o1, o2) ->
                Long.compare(o2.getValue().getEnergyUsageTotal(), o1.getValue().getEnergyUsageTotal()));
            break;
          case "totalPenalty":
            list.sort((o1, o2) ->
                Long.compare(o2.getValue().getEnergyPenaltyTotal(), o1.getValue().getEnergyPenaltyTotal()));
            break;
          case "trxBurn":
            list.sort((o1, o2) ->
                Long.compare(o2.getValue().getTrxBurn(), o1.getValue().getTrxBurn()));
            break;
          case "txCount":
            list.sort((o1, o2) ->
                Long.compare(o2.getValue().getTxTotalCount(), o1.getValue().getTxTotalCount()));
            break;
          default:
            list.sort((o1, o2) ->
                Long.compare(o2.getValue().getEnergyUsage(), o1.getValue().getEnergyUsage()));
        }
        response.getWriter().println("Total" + " = " + list.get(0).getValue());
        response.getWriter().println("Top 10 contracts (sorted by " + sortedBy + "):");
        for (int i = 1; i <= 10 && i < list.size(); i++) {
          Map.Entry<WrappedByteArray, ContractStateCapsule> e = list.get(i);
          byte[] key = Arrays.copyOfRange(e.getKey().getBytes(), 5, 26);
          response.getWriter().println(StringUtil.encode58Check(key) + " = " + e.getValue());
        }
      } else {
        response.getWriter().println(css.getByCycle(Commons.decodeFromBase58Check(address),
            Long.parseLong(cycleNumber)));
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
