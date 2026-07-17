package org.tron.core.services.http.tracker;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.json.JSONObject;

/**
 * fast-sync-stats: 移植自 track_dynamic_energy/BaseTrackerServlet。
 * 精简掉了原参考分支里依赖的 ContractStateStore(本分支没有)。
 *
 * 与参考的差异:默认 cycle_number 取 currentCycle - 1(最近一个已统计完成的周期),
 * 否则维护周期内查询永远拿不到数据(刚结束周期 N 的统计写在 SS_N_*,而 currentCycle
 * 此时已被 applyBlock 推进为 N+1)。参考实现的 currentCycle 默认是已知 UX 坑。
 */
@Slf4j
public abstract class BaseTrackerServlet extends RateLimiterServlet {

  @Autowired
  protected DynamicPropertiesStore dps;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("application/json; charset=utf-8");

    long current = this.dps.getCurrentCycleNumber();
    long cycleNumber = request.getParameter("cycle_number") == null
        ? Math.max(0, current - 1)
        : Long.parseLong(request.getParameter("cycle_number"));
    cycleNumber = Math.min(cycleNumber, current);

    long cycleCount = request.getParameter("cycle_count") == null
        ? 1
        : Long.parseLong(request.getParameter("cycle_count"));

    try {
      responseGet(new TrackerRequest(request, response, cycleNumber, cycleCount));
    } catch (MissingParameterException e) {
      JSONObject res = new JSONObject();
      res.put("code", 500);
      res.put("error", e.getMessage());
      response.getWriter().println(res.toJSONString());
    }
  }

  protected String mustGetParameter(TrackerRequest ctx, String name) {
    String value = ctx.request.getParameter(name);
    if (value == null) {
      throw new MissingParameterException(name);
    }
    return value;
  }

  protected String mayGetParameter(TrackerRequest ctx, String name, String defaultValue) {
    String value = ctx.request.getParameter(name);
    return value == null ? defaultValue : value;
  }

  abstract void responseGet(TrackerRequest ctx) throws IOException, MissingParameterException;

  protected static class TrackerRequest {
    final HttpServletRequest request;
    final HttpServletResponse response;
    final long cycleNumber;
    final long cycleCount;

    TrackerRequest(HttpServletRequest request, HttpServletResponse response,
        long cycleNumber, long cycleCount) {
      this.request = request;
      this.response = response;
      this.cycleNumber = cycleNumber;
      this.cycleCount = cycleCount;
    }
  }
}
