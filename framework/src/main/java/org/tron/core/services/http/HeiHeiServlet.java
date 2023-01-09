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
      String address = request.getParameter("address");
      String cycleNumber = request.getParameter("cycle_number");
      if (cycleNumber == null) {
        DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
        cycleNumber = String.valueOf(dps.getCurrentCycleNumber());
      }
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();
      response.getWriter().println("Current cycle number:" + cycleNumber);
      if (address == null) {
        response.getWriter().println("Top 10 contracts:");
        Map<WrappedByteArray, ContractStateCapsule> contracts =
            css.prefixQuery(cycleNumber.getBytes());
        List<Map.Entry<WrappedByteArray, ContractStateCapsule>> list =
            new LinkedList<>(contracts.entrySet());
        list.sort((o1, o2) ->
            Long.compare(o2.getValue().getEnergyUsage(), o1.getValue().getEnergyUsage()));
        for (int i = 0; i < 10 && i < list.size(); i++) {
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
