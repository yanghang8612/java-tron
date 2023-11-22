package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db2.common.WrappedByteArray;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class TopTRC10Servlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

      long cycleNumber = request.getParameter("cycle_number") == null ?
              dps.getCurrentCycleNumber() : Long.parseLong(request.getParameter("cycle_number"));
      cycleNumber = Math.min(cycleNumber, dps.getCurrentCycleNumber());
      long cycleCount = request.getParameter("cycle_count") == null ?
              1 : Long.parseLong(request.getParameter("cycle_count"));

      Map<WrappedByteArray, Long> result = new HashMap<>();
      for (int i = 0; i < cycleCount; i++) {
        byte[] prefix = ((cycleNumber + i) + "-").getBytes();
        byte[] key = new byte[prefix.length + 1];
        System.arraycopy(prefix, 0, key, 0, prefix.length);
        key[prefix.length] = 0x51;
        Map<WrappedByteArray, ContractStateCapsule> data = css.prefixQuery(key);
        data.forEach((k, v) -> result.put(k, result.getOrDefault(k, 0L) + v.getTxTrc10Count()));
      }

      List<Map.Entry<WrappedByteArray, Long>> list = new LinkedList<>(result.entrySet());
      list.sort((o1, o2) -> Long.compare(o2.getValue(), o1.getValue()));

      response.getWriter().println("\nTop 20 TRC10:\n");
      for (int i = 0; i < 20; i++) {
        response.getWriter().println(new String(list.get(i).getKey().getBytes()) +
                ": " + list.get(i).getValue());
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
