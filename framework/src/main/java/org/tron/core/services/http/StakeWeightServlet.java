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
      long net2;
      long energy2;
      long totalNet;
      long totalEnergy;
      String cycleParam = request.getParameter("cycle_number");
      if (cycleParam == null) {
        // 不带 cycle_number:返回 live 当前值
        totalNet = dps.getTotalNetWeight();
        totalEnergy = dps.getTotalEnergyWeight();
        net2 = dps.getTotalNetWeight2();
        energy2 = dps.getTotalEnergyWeight2();
      } else {
        // 带 cycle_number:读 SW_<cycle> 快照(由 MaintenanceManager 在每个周期末写入)
        long cycle = Long.parseLong(cycleParam);
        long[] snap = dps.getCycleStakeWeights(cycle);
        if (snap == null) {
          com.alibaba.fastjson.JSONObject err = new com.alibaba.fastjson.JSONObject();
          err.put("error", "no stake weight snapshot for cycle " + cycle);
          response.getWriter().println(err.toJSONString());
          return;
        }
        net2 = snap[0];
        energy2 = snap[1];
        totalNet = snap[2];
        totalEnergy = snap[3];
      }
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
