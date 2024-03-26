package org.tron.core.services.http.tracker;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
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
public class TopUserServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

      long cycleNumber = request.getParameter("cycle_number") == null ?
              dps.getCurrentCycleNumber() : Long.parseLong(request.getParameter("cycle_number"));
      cycleNumber = Math.min(cycleNumber, dps.getCurrentCycleNumber());
      long cycleCount = request.getParameter("cycle_count") == null ?
              1 : Long.parseLong(request.getParameter("cycle_count"));

      Map<ByteString, ContractStateCapsule> result = css.getMergedDataWithinCycles(cycleNumber, cycleCount, false);
      List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(result.entrySet());
      list.sort((o1, o2) ->
              Long.compare(o2.getValue().getTrxBurn(), o1.getValue().getTrxBurn()));

      for (int i = 0; i < 10000; i++) {
        response.getWriter().printf("%s %s %d%n",
                StringUtil.encode58Check(list.get(i).getKey().toByteArray()),
                list.get(i).getValue().getTrxBurn(),
                list.get(i).getValue().getTxCount());
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
