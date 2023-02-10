package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.db.Manager;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

@Component
@Slf4j(topic = "API")
public class SlackServlet extends RateLimiterServlet {

  @Autowired
  private Manager dbManage;

  @Autowired
  private DynamicPropertiesStore dps;

  @Autowired
  private ContractStateStore css;

  protected void doGet(HttpServletRequest req, HttpServletResponse resp) {

  }

  protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
    String cmd = req.getParameter("command");
    String text = req.getParameter("text");
    long cycle = dps.getCurrentCycleNumber();
    try {
      cycle = Integer.parseInt(text);
    } catch (Exception ignored) { }
    if ("/cycle".equals(cmd)) {
      dbManage.doDynamicEnergyCycleStats(cycle);
    } else if ("/day".equals(cmd)) {
      dbManage.doDynamicEnergyDayStats(cycle);
    } else if ("/month".equals(cmd)) {

    }
    System.out.println(cmd);
    System.out.println(text);
    resp.setStatus(200);
  }
}
