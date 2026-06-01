package org.tron.core.services.http.tracker;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.store.StakerStatStore;
import org.tron.protos.Protocol;

/**
 * fast-sync-stats: 移植自 track_dynamic_energy/StakerStatsServlet。
 * 路由 /staker_stats(非 /wallet 下),参数 cycle_number/address/threshold。
 */
@Component
@Slf4j(topic = "API")
public class StakerStatsServlet extends BaseTrackerServlet {

  @Autowired
  private StakerStatStore stakerStatStore;

  @Override
  void responseGet(TrackerRequest ctx) throws IOException, MissingParameterException {
    List<Protocol.StakerStat> stats = stakerStatStore.getStakerStat(ctx.cycleNumber);
    stats.sort(Comparator.comparingLong(Protocol.StakerStat::getStakedTrxForEnergy).reversed());

    String addressStr = mayGetParameter(ctx, "address", "");
    if (addressStr.isEmpty()) {
      for (Protocol.StakerStat stat : stats) {
        ctx.response.getWriter().printf("%s %d %d %d%n",
            StringUtil.encode58Check(stat.getAddress().toByteArray()),
            stat.getStakedTrxForEnergy(),
            stat.getMeu(),
            stat.getDelegateStatsCount());
      }
    } else {
      byte[] address = Commons.decode58Check(addressStr);
      if (address != null) {
        String thresholdStr = mayGetParameter(ctx, "threshold", "0");
        long threshold = Long.parseLong(thresholdStr);
        for (Protocol.StakerStat stat : stats) {
          if (stat.getAddress().equals(ByteString.copyFrom(address))) {
            List<Protocol.StakerStat.DelegateStat> delegates =
                new ArrayList<>(stat.getDelegateStatsList());
            delegates.sort(Comparator
                .comparingLong(Protocol.StakerStat.DelegateStat::getAmount).reversed());
            for (Protocol.StakerStat.DelegateStat delegate : delegates) {
              if (delegate.getAmount() >= threshold) {
                ctx.response.getWriter().printf("%s %d %d%n",
                    StringUtil.encode58Check(delegate.getTo().toByteArray()),
                    delegate.getAmount(),
                    delegate.getMeu());
              }
            }
            return;
          }
        }
        ctx.response.getWriter().println("Not found");
      }
    }
  }
}
