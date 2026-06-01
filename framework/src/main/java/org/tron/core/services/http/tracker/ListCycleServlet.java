package org.tron.core.services.http.tracker;

import com.alibaba.fastjson.JSONObject;
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
      org.tron.core.store.TrackerStore trackerStore =
          ChainBaseManager.getInstance().getTrackerStore();

      JSONObject resObj = new JSONObject();
      long currentCycleNumber = dps.getCurrentCycleNumber();
      resObj.put("current", currentCycleNumber);

      long startCycleNumberInDay = (currentCycleNumber - 3) / 4 * 4 + 3;
      resObj.put("today", startCycleNumberInDay);

      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
      for (int i = 0; i < 31; i++) {
        long cycleStartTime = ChainBaseManager.getInstance()
                .getBlockByNum(trackerStore.getCycleEndBlockNumber(startCycleNumberInDay - 1))
                .getTimeStamp();
        resObj.put(sdf.format(new Date(cycleStartTime)), startCycleNumberInDay);
        startCycleNumberInDay -= 4;
      }

      response.getWriter().println(resObj.toJSONString());
      response.setContentType("application/json");
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
