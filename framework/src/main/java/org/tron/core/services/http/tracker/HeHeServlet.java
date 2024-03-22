package org.tron.core.services.http.tracker;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.services.http.Util;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.ContractStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class HeHeServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
      ContractStore cs = ChainBaseManager.getInstance().getContractStore();
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

      long cycleNumber = request.getParameter("cycle_number") == null ?
              dps.getCurrentCycleNumber() : Long.parseLong(request.getParameter("cycle_number"));
      cycleNumber = Math.min(cycleNumber, dps.getCurrentCycleNumber());
      long cycleCount = request.getParameter("cycle_count") == null ?
              1 : Long.parseLong(request.getParameter("cycle_count"));

      response.getWriter().println("Total" + " = " +
              css.getIntervalData(cycleNumber, cycleCount, "total".getBytes()) + "\n");

      response.getWriter().println("Big" + " = " +
              css.getIntervalData(cycleNumber, cycleCount, "big".getBytes()) + "\n");

      response.getWriter().println("Small" + " = " +
              css.getIntervalData(cycleNumber, cycleCount, "small".getBytes()) + "\n");

      if (request.getParameter("address") == null) {
        Map<ByteString, ContractStateCapsule> result = css.getMergedDataWithinCycles(cycleNumber, cycleCount, true);
        List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(result.entrySet());

        String sortedBy = request.getParameter("sorted_by");
        if (sortedBy == null) {
          sortedBy = "totalUsage";
        }
        switch (sortedBy) {
          case "usage":
            list.sort((o1, o2) ->
                    Long.compare(o2.getValue().getEnergyUsage(), o1.getValue().getEnergyUsage()));
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
                    Long.compare(o2.getValue().getEnergyUsageTotal(), o1.getValue().getEnergyUsageTotal()));
        }

        response.getWriter().println("\nTop 100 contracts (sorted by " + sortedBy + "):\n");
        for (int i = 0; i < 100; i++) {
          response.getWriter().println(StringUtil.encode58Check(list.get(i).getKey().toByteArray()) +
                  " = " + list.get(i).getValue() + "\n");
        }
      } else {
        byte[] addr = Commons.decodeFromBase58Check(request.getParameter("address"));
        if (!cs.has(addr)) {
          addr[0] = (byte) 0x42;
        }
        ContractStateCapsule result = css.getIntervalData(cycleNumber, cycleCount, addr, false);
        response.getWriter().println(request.getParameter("address") + " = " + result);
        response.getWriter().println("Delegated accounts:");
        for (String acc : result.getDelegatedAccounts()) {
          response.getWriter().println(acc);
        }
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
