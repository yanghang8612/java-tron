package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Base58;
import org.tron.core.ChainBaseManager;
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
      response.getWriter().println(cycleNumber);
      if (address == null) {
        response.getWriter().println(css.prefixQuery(cycleNumber.getBytes()));
      } else {
        response.getWriter().println(css.getByCycle(Base58.decode(address), Long.parseLong(cycleNumber)));
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
