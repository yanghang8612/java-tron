package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class TopNotUSDTServlet extends RateLimiterServlet {

    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
        ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

        String cycleNumber = request.getParameter("cycle_number");
        if (cycleNumber == null) {
            cycleNumber = String.valueOf(dps.getCurrentCycleNumber());
        }

        Map<String, ContractStateCapsule> today =
                css.getMergedDataWithinCycles(Long.parseLong(cycleNumber), 4, true);

        Map<String, ContractStateCapsule> yesterday =
                css.getMergedDataWithinCycles(Long.parseLong(cycleNumber) - 4, 4, true);

        yesterday.forEach((k, v) -> {
            if (today.containsKey(k)) {
                today.get(k).addTxTotalCount(-v.getTxTotalCount());
            } else {
                today.put(k, v);
                today.get(k).addTxTotalCount(-2 * v.getTxTotalCount());
            }
        });

        List<Map.Entry<String, ContractStateCapsule>> list = new LinkedList<>(today.entrySet());
        list.sort((o1, o2) -> Long.compare(o2.getValue().getTxTotalCount(), o1.getValue().getTxTotalCount()));
        for (Map.Entry<String, ContractStateCapsule> e : list) {
            try {
                response.getWriter().println(e.getKey() + " = " + e.getValue());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
