package org.tron.core.services.http.tracker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.services.http.Util;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;
import java.util.Date;

@Component
@Slf4j(topic = "API")
public class ListCycleServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();

      long currentCycleNumber = dps.getCurrentCycleNumber();
      long currentCycleStartTime = ChainBaseManager.getInstance()
              .getBlockByNum(dps.getCycleEndBlockNumber(currentCycleNumber - 1)).getTimeStamp();
      SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
      response.getWriter().println("Current cycle number: " + currentCycleNumber + ", start: "
              + sdf.format(new Date(currentCycleStartTime)) + ", end: "
              + sdf.format(new Date(currentCycleStartTime + 6 * 60 * 60 * 1000)));

      long todayCycleNumber = (currentCycleNumber - 3) / 4 * 4 + 3;
      for (int i = 0; i < 30; i++) {
        long cycleStartTime = ChainBaseManager.getInstance()
                .getBlockByNum(dps.getCycleEndBlockNumber(todayCycleNumber - 1)).getTimeStamp();
        response.getWriter().println(new SimpleDateFormat("MM-dd E").format(new Date(cycleStartTime)) + ": " + todayCycleNumber);

        todayCycleNumber -= 4;
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
