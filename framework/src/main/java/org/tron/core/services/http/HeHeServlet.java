package org.tron.core.services.http;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.ContractStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;
import java.util.Date;
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

      long currentCycleNumber = dps.getCurrentCycleNumber();
      long currentCycleStartTime = ChainBaseManager.getInstance()
              .getBlockByNum(dps.getCycleEndBlockNumber(currentCycleNumber - 1)).getTimeStamp();
      SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
      response.getWriter().println("Current cycle number: " + currentCycleNumber + ", start: "
              + sdf.format(new Date(currentCycleStartTime)) + ", end: "
              + sdf.format(new Date(currentCycleStartTime + 6 * 60 * 60 * 1000)));
      long queryCycleStartTime = ChainBaseManager.getInstance()
              .getBlockByNum(dps.getCycleEndBlockNumber(cycleNumber - 1)).getTimeStamp();
      response.getWriter().println("Query cycle number: " + cycleNumber);
      response.getWriter().println("Query cycle count: " + cycleCount);
      response.getWriter().println("Query start time: " + sdf.format(new Date(queryCycleStartTime)));
      response.getWriter().println("Query end time: "
              + sdf.format(new Date(queryCycleStartTime + 6 * 60 * 60 * 1000 * cycleCount)));

      if (request.getParameter("address") == null) {
        Map<ByteString, ContractStateCapsule> result = css.getMergedDataWithinCycles(cycleNumber, cycleCount, true);
        List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(result.entrySet());

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

        response.getWriter().println("Total" + " = " +
                css.getIntervalData(cycleNumber, cycleCount, "total".getBytes()));
        response.getWriter().println("\nTop contracts (sorted by " + sortedBy + "):\n");
        for (Map.Entry<ByteString, ContractStateCapsule> e : list) {
            response.getWriter().println(e.getKey() + " = " + e.getValue());
        }
      } else {
        response.getWriter().println("Total" + " = " +
                css.getIntervalData(cycleNumber, cycleCount, "total".getBytes()));
        byte[] addr = Commons.decodeFromBase58Check(request.getParameter("address"));
        if (!cs.has(addr)) {
          addr[0] = (byte) 0x42;
        }
        response.getWriter().println(request.getParameter("address") + " = " +
                css.getIntervalData(cycleNumber, cycleCount, addr));
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
