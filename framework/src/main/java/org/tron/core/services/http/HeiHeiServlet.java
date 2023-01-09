package org.tron.core.services.http;

import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
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
      response.getWriter().println("current cycle number:" + cycleNumber);
      if (address == null) {
        Map<WrappedByteArray, ContractStateCapsule> contracts =
            css.prefixQuery(cycleNumber.getBytes());
        contracts.forEach((k, v) -> {
          try {
            response.getWriter().println(Hex.toHexString(k.getBytes()) + " = " + v);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
      } else {
        response.getWriter().println(css.getByCycle(Commons.decodeFromBase58Check(address),
            Long.parseLong(cycleNumber)));
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
