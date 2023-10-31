package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

@Component
@Slf4j(topic = "API")
public class HeHeServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

      String address = request.getParameter("address");
      String cycleNumberStr = request.getParameter("cycle_number");
      String cycleCountStr = request.getParameter("cycle_count");
      if (cycleNumberStr == null) {
        cycleNumberStr = String.valueOf(dps.getCurrentCycleNumber());
      }
      if (cycleCountStr == null) {
        cycleCountStr = "1";
      }
      response.getWriter().println("Current cycle number: " + dps.getCurrentCycleNumber());
      response.getWriter().println("Query cycle number: " + cycleNumberStr + "\n");
      response.getWriter().println("Query cycle count: " + cycleCountStr + "\n");

      if (address == null) {
        Map<String, ContractStateCapsule> result = new HashMap<>();
        long cycleNumber = Long.parseLong(cycleNumberStr);
        for (int i = 0; i < Long.parseLong(cycleCountStr); i++) {
          Map<WrappedByteArray, ContractStateCapsule> contracts =
                  css.prefixQuery(Long.toString(cycleNumber).getBytes());

          contracts.forEach((k, v) -> {
            byte[] key = Arrays.copyOfRange(k.getBytes(), 5, 26);
            if (key[0] == 0x41) {
              String addr = StringUtil.encode58Check(key);
              if (result.containsKey(addr)) {
                result.get(addr).merge(v);
              } else {
                result.put(addr, v);
              }
            }
          });

          cycleNumber -= 1;
        }


        List<Map.Entry<String, ContractStateCapsule>> list =
            new LinkedList<>(result.entrySet());
        String sortedBy = request.getParameter("sorted_by");
        if (sortedBy == null) {
          sortedBy = "usage";
        }
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
        ContractStateCapsule total = css.getDayState(Long.parseLong(cycleNumberStr), "total".getBytes());
        response.getWriter().println("Total" + " = " + total);
        response.getWriter().println("\nTop 100 contracts (sorted by " + sortedBy + "):\n");
        for (int i = 0; i < 100 && i < list.size(); i++) {
          Map.Entry<String, ContractStateCapsule> e = list.get(i);
          response.getWriter().println(e.getKey() + " = " + e.getValue());
        }
      } else {
        response.getWriter().println("Total" + " = "
            + css.getDayState(Long.parseLong(cycleNumberStr), "total".getBytes()));
        response.getWriter().println(address + " = "
            + css.getDayState(Long.parseLong(cycleNumberStr), Commons.decodeFromBase58Check(address)));
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
