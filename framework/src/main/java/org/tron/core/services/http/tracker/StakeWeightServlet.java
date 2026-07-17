package org.tron.core.services.http.tracker;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.store.TrackerStore;
import org.tron.json.JSONObject;

/**
 * fast-sync-stats: /stake_weight — 全网质押权重按 stake1.0/2.0 拆分。
 * 与 /staker_stats、/list_cycle 同风格:tracker 包、BaseTrackerServlet、snake_case 根路由。
 *
 * 行为:
 *  - 默认 cycle_number = currentCycle - 1(继承自 BaseTrackerServlet)。
 *    若该周期还没有快照(节点刚启动/未走过维护点),回退为 live 当前值。
 *  - 显式传 cycle_number=N 但 SW_N 不存在 → 返回 error,避免给历史 cycle 返回 live 引起误导。
 */
@Component
@Slf4j(topic = "API")
public class StakeWeightServlet extends BaseTrackerServlet {

  @Autowired
  private TrackerStore trackerStore;

  @Override
  void responseGet(TrackerRequest ctx) throws IOException, MissingParameterException {
    long net2;
    long energy2;
    long totalNet;
    long totalEnergy;

    long[] snap = trackerStore.getCycleStakeWeights(ctx.cycleNumber);
    String cycleParam = ctx.request.getParameter("cycle_number");

    if (snap != null) {
      net2 = snap[0];
      energy2 = snap[1];
      totalNet = snap[2];
      totalEnergy = snap[3];
    } else if (cycleParam == null) {
      // 默认查询且快照尚未写入(预热期):回退到 live
      totalNet = dps.getTotalNetWeight();
      totalEnergy = dps.getTotalEnergyWeight();
      net2 = trackerStore.getTotalNetWeight2();
      energy2 = trackerStore.getTotalEnergyWeight2();
    } else {
      JSONObject err = new JSONObject();
      err.put("error", "no stake weight snapshot for cycle " + ctx.cycleNumber);
      ctx.response.getWriter().println(err.toJSONString());
      return;
    }

    JSONObject obj = new JSONObject();
    obj.put("bandwidth_stake2", net2);
    obj.put("energy_stake2", energy2);
    obj.put("bandwidth_stake1", Math.max(0, totalNet - net2));
    obj.put("energy_stake1", Math.max(0, totalEnergy - energy2));
    obj.put("bandwidth_total", totalNet);
    obj.put("energy_total", totalEnergy);
    ctx.response.getWriter().println(obj.toJSONString());
  }
}
