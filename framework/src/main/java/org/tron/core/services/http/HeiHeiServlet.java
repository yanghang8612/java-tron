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
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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
      response.getWriter().println("Query cycle number: " + cycleNumber + "\n");

      if (address == null) {
        Map<WrappedByteArray, ContractStateCapsule> contracts =
            css.prefixQuery(cycleNumber.getBytes());
        List<Map.Entry<WrappedByteArray, ContractStateCapsule>> list =
            new LinkedList<>(contracts.entrySet());
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
        ContractStateCapsule total = css.getDayState(Long.parseLong(cycleNumber), "total".getBytes());
        ContractStateCapsule big = css.getDayState(Long.parseLong(cycleNumber), "big".getBytes());
        ContractStateCapsule small = css.getDayState(Long.parseLong(cycleNumber), "small".getBytes());
        byte[] addr = Commons.decodeFromBase58Check("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
        ContractStateCapsule usdt = css.getDayState(Long.parseLong(cycleNumber), addr);

        response.getWriter().println(String.format("%d %d %d %d %d %.2f %d %d %d %d %d %d",
                total.getTxTotalCount(),
                small.getTxTotalCount() + big.getTxTotalCount(),
                total.getTxTotalCount() - small.getTxTotalCount() - big.getTxTotalCount(),
                total.getTrxBurn() / 1000000,
                total.getBandwidthStake() + total.getEnergyStake(),
                100.0 * total.getEnergyStake() / (total.getBandwidthStake() + total.getEnergyStake()),
                total.getEnergyStake(),
                total.getEnergyUsage(),
                total.getEnergyUsage() - total.getTrxBurn() / 420,
                total.getTrxBurn() / 420,
                small.getTxTotalCount(),
                big.getTxTotalCount()));

        response.getWriter().println(String.format("%d %d %d",
                total.getBandwidthStake() + total.getEnergyStake(),
                total.getBandwidthStake() + total.getEnergyStake() - total.getBandwidthStake2() - total.getEnergyStake2(),
                total.getBandwidthStake2() + total.getEnergyStake2()));

        response.getWriter().println(String.format("%d %d %d %d %d %d %d %d %d %d",
                usdt.getTxTotalCount(),
                usdt.getTrxBurn() / 1000000,
                usdt.getEnergyUsageTotal(),
                usdt.getEnergyPenaltyTotal(),
                small.getTxTotalCount(),
                small.getEnergyUsageTotal() - small.getEnergyUsage(),
                small.getEnergyUsage(),
                big.getTxTotalCount(),
                big.getEnergyUsageTotal() - big.getEnergyUsage(),
                big.getEnergyUsage()));

        response.getWriter().println(String.format("%d %d %d %d %d %d",
                total.getTxTrxCount(),
                total.getTxTrc10Count(),
                usdt.getTxTotalCount(),
                total.getTxTotalCount() - usdt.getTxTotalCount(),
                total.getTxCount() - total.getTxTrxCount() - total.getTxTrc10Count() - total.getTxTotalCount(),
                total.getTxCount()));

        response.getWriter().println("Total" + " = " + total);
        response.getWriter().println("Big" + " = " + big);
        response.getWriter().println("Small" + " = " + small);
        response.getWriter().println("USDT" + " = " + usdt);
        response.getWriter().println("\nTop 10 contracts (sorted by " + sortedBy + "):\n");
        for (int i = 0; i < 11 && i < list.size(); i++) {
          Map.Entry<WrappedByteArray, ContractStateCapsule> e = list.get(i);
          byte[] key = Arrays.copyOfRange(e.getKey().getBytes(), 5, 26);
          if (key[0] == 0x41) {
            response.getWriter().println(StringUtil.encode58Check(key) + " = " + e.getValue());
          }
        }
      } else {
        response.getWriter().println("Total" + " = "
            + css.getDayState(Long.parseLong(cycleNumber), "total".getBytes()));
        response.getWriter().println(address + " = "
            + css.getDayState(Long.parseLong(cycleNumber), Commons.decodeFromBase58Check(address)));
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
