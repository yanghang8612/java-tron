package org.tron.core.services.http;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.store.DynamicPropertiesStore;

@Component
@Slf4j(topic = "API")
public class StakeWeightServlet extends RateLimiterServlet {

  @Autowired
  private DynamicPropertiesStore dps;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      long totalNet = dps.getTotalNetWeight();
      long totalEnergy = dps.getTotalEnergyWeight();
      long net2 = dps.getTotalNetWeight2();
      long energy2 = dps.getTotalEnergyWeight2();
      com.alibaba.fastjson.JSONObject obj = new com.alibaba.fastjson.JSONObject();
      obj.put("bandwidth_stake2", net2);
      obj.put("energy_stake2", energy2);
      obj.put("bandwidth_stake1", Math.max(0, totalNet - net2));
      obj.put("energy_stake1", Math.max(0, totalEnergy - energy2));
      obj.put("bandwidth_total", totalNet);
      obj.put("energy_total", totalEnergy);
      response.getWriter().println(obj.toJSONString());
    } catch (Exception e) {
      logger.error("", e);
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    doGet(request, response);
  }
}
