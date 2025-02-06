package org.tron.core.services.http.tracker;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.protos.Protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@Slf4j(topic = "API")
public class StakerStatsServlet extends BaseTrackerServlet {

    @Override
    void responseGet() throws IOException, MissingParameterException {
        List<Protocol.StakerStat> stats = dps.getStakerStat(cycleNumber);
        stats.sort(Comparator.comparingLong(Protocol.StakerStat::getStakedTrxForEnergy).reversed());

        String addressStr = mayGetParameter("address", "");
        if (addressStr.isEmpty()) {
            for (Protocol.StakerStat stat : stats) {
                response.getWriter().printf("%s %d %d %d%n",
                    StringUtil.encode58Check(stat.getAddress().toByteArray()),
                    stat.getStakedTrxForEnergy(),
                    stat.getMeu(),
                    stat.getDelegateStatsCount());
            }
        } else {
            byte[] address = Commons.decode58Check(addressStr);
            if (address != null) {
                String thresholdStr = mayGetParameter("threshold", "0");
                long threshold = Long.parseLong(thresholdStr);
                for (Protocol.StakerStat stat : stats) {
                    if (stat.getAddress().equals(ByteString.copyFrom(address))) {
                        List<Protocol.StakerStat.DelegateStat> delegates = new ArrayList<>(stat.getDelegateStatsList());
                        delegates.sort(Comparator.comparingLong(Protocol.StakerStat.DelegateStat::getAmount).reversed());
                        for (Protocol.StakerStat.DelegateStat delegate : delegates) {
                            if (delegate.getAmount() >= threshold) {
                                response.getWriter().printf("%s %d %d%n",
                                    StringUtil.encode58Check(delegate.getTo().toByteArray()),
                                    delegate.getAmount(),
                                    delegate.getMeu());
                            }
                        }
                        return;
                    }
                }
                response.getWriter().println("Not found");
            }
        }
    }
}
