package org.tron.core.services.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StakerStatStore;
import org.tron.protos.Protocol;

import com.google.protobuf.ByteString;

@Component
@Slf4j(topic = "API")
public class StakerStatsServlet extends RateLimiterServlet {

  @Autowired
  private StakerStatStore stakerStatStore;

  @Autowired
  private DynamicPropertiesStore dps;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      response.setContentType("text/plain");
      // 默认返回最近一个已统计完成的周期:doStats 在维护点把刚结束的周期 N 落库后,
      // applyBlock 已把 currentCycle 推进为 N+1,故"最新可用"周期 = currentCycle - 1。
      long cycleNumber = request.getParameter("cycle_number") == null
          ? Math.max(0, dps.getCurrentCycleNumber() - 1)
          : Long.parseLong(request.getParameter("cycle_number"));
      List<Protocol.StakerStat> stats = stakerStatStore.getStakerStat(cycleNumber);
      stats.sort(Comparator.comparingLong(Protocol.StakerStat::getStakedTrxForEnergy).reversed());
      String addressStr = request.getParameter("address") == null
          ? "" : request.getParameter("address");
      if (addressStr.isEmpty()) {
        for (Protocol.StakerStat stat : stats) {
          response.getWriter().printf("%s %d %d %d%n",
              StringUtil.encode58Check(stat.getAddress().toByteArray()),
              stat.getStakedTrxForEnergy(), stat.getMeu(), stat.getDelegateStatsCount());
        }
      } else {
        byte[] address = Commons.decode58Check(addressStr);
        if (address == null) {
          response.getWriter().println("invalid address");
          return;
        }
        long threshold = request.getParameter("threshold") == null ? 0
            : Long.parseLong(request.getParameter("threshold"));
        for (Protocol.StakerStat stat : stats) {
          if (stat.getAddress().equals(ByteString.copyFrom(address))) {
            List<Protocol.StakerStat.DelegateStat> ds =
                new ArrayList<>(stat.getDelegateStatsList());
            ds.sort(Comparator.comparingLong(Protocol.StakerStat.DelegateStat::getAmount)
                .reversed());
            for (Protocol.StakerStat.DelegateStat d : ds) {
              if (d.getAmount() >= threshold) {
                response.getWriter().printf("%s %d %d%n",
                    StringUtil.encode58Check(d.getTo().toByteArray()),
                    d.getAmount(), d.getMeu());
              }
            }
            return;
          }
        }
        response.getWriter().println("Not found");
      }
    } catch (Exception e) {
      logger.error("", e);
      try {
        response.getWriter().println(e.getMessage());
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    doGet(request, response);
  }
}
