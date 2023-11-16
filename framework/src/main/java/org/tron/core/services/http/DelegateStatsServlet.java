package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Slf4j(topic = "API")
public class DelegateStatsServlet extends RateLimiterServlet {

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
        ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

        long cycleNumber;
        if (request.getParameter("cycle_number") == null) {
            cycleNumber = dps.getCurrentCycleNumber();
        } else {
            cycleNumber = Long.parseLong(request.getParameter("cycle_number"));
        }

        long cycleCount;
        if (request.getParameter("cycle_count") == null) {
            cycleCount = 1;
        } else {
            cycleCount = Long.parseLong(request.getParameter("cycle_count"));
        }

        byte[] ownerAddr = null;
        if (request.getParameter("address") != null) {
            ownerAddr = Commons.decode58Check(request.getParameter("address"));
        } else {
            response.getWriter().println("Please input address");
        }

        ownerAddr[0] = 0x42;
        ContractStateCapsule owner = css.getIntervalData(cycleNumber, cycleCount, ownerAddr);
        ContractStateCapsule result = new ContractStateCapsule(0);
        owner.getDelegatedAccountsList().forEach(acc -> {
            byte[] addr = Commons.decode58Check(acc);
            addr[0] = 0x42;
            ContractStateCapsule consumer = css.getIntervalData(cycleNumber, cycleCount, addr);
            result.addEnergyUsage(consumer.getEnergyUsage());
            result.addTrxBurn(consumer.getTrxBurn());
        });

        response.getWriter().println(result);
    }
}
