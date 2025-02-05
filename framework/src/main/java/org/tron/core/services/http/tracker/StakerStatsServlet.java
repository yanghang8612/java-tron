package org.tron.core.services.http.tracker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
import org.tron.protos.Protocol;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j(topic = "API")
public class StakerStatsServlet extends BaseTrackerServlet {

    @Override
    void responseGet() throws IOException, MissingParameterException {
        List<Protocol.StakerStat> stats = dps.getStakerStat(cycleNumber);
        response.getWriter().println("Staker stats:");
        for (Protocol.StakerStat stat : stats) {
            response.getWriter().printf("%s: %d, %d%n",
                StringUtil.encode58Check(stat.getAddress().toByteArray()),
                stat.getStakedTrxForEnergy(),
                stat.getMeu());
            for (Protocol.StakerStat.DelegateStat delegate : stat.getDelegateStatsList()) {
                response.getWriter().printf(" |- %s: %d, %d%n",
                    StringUtil.encode58Check(delegate.getTo().toByteArray()),
                    delegate.getAmount(),
                    delegate.getMeu());
            }
            response.getWriter().println();
        }
    }
}
