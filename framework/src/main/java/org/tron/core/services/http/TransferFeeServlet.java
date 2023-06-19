package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

@Component
@Slf4j(topic = "API")
public class TransferFeeServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

      String cycleNumber = request.getParameter("cycle_number");
      if (cycleNumber == null) {
        cycleNumber = String.valueOf(dps.getCurrentCycleNumber());
      }
      response.getWriter().println("Current cycle number: " + dps.getCurrentCycleNumber());
      response.getWriter().println("Query cycle number: " + cycleNumber + "\n");

      for (int i = 0; i < 4 * 30; i++) {
        long cycle = Long.parseLong(cycleNumber) - i;
        ContractStateCapsule csc = css.getByCycle("total".getBytes(), cycle);
        if (csc == null) {
          break;
        }
        response.getWriter().printf("#%d %.2f %.2f %.2f %.2f%n",
                cycle,
                (double) csc.getEnergyPrice() * 14650 / 1e6,
                (double) csc.getEnergyPrice() * 29650 / 1e6,
                (double) csc.getGasPrice() * 41309 / 1e9,
                (double) csc.getGasPrice() * 63209 / 1e9);
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
