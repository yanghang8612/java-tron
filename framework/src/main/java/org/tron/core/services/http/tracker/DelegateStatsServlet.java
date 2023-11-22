package org.tron.core.services.http.tracker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.capsule.ContractStateCapsule;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Slf4j(topic = "API")
public class DelegateStatsServlet extends BaseTrackerServlet {

    @Override
    void responseGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getParameter("address") == null) {
            response.getWriter().println("Please input address");
            return;
        }
        byte[] ownerAddr = Commons.decode58Check(request.getParameter("address"));;
        ownerAddr[0] = 0x42;
        ContractStateCapsule owner = css.getIntervalData(cycleNumber, cycleCount, ownerAddr, false);
        ContractStateCapsule result = new ContractStateCapsule(0);
        for (String acc : owner.getDelegatedAccounts()) {
            byte[] addr = Commons.decode58Check(acc);
            addr[0] = 0x42;
            ContractStateCapsule consumer = css.getIntervalData(cycleNumber, cycleCount, addr);
            result.addEnergyUsage(consumer.getEnergyUsage());
            result.addTrxBurn(consumer.getTrxBurn());
        }

        response.getWriter().println("Stats = " + result);
    }
}
