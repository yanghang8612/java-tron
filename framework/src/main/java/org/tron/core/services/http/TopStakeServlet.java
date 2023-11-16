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
import java.util.stream.Collectors;

@Component
@Slf4j(topic = "API")
public class TopStakeServlet extends RateLimiterServlet {

    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
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

        Map<String, ContractStateCapsule> data = css.getMergedDataWithinCycles(cycleNumber, cycleCount, false);
        List<Map.Entry<String, ContractStateCapsule>> list = new LinkedList<>(data.entrySet());
        list = list.stream()
                .filter(e -> e.getValue().getStake2() != 0 ||
                        e.getValue().getUnstake() != 0 ||
                        e.getValue().getUnstake2() != 0)
                .collect(Collectors.toList());

        try {
            list.sort((o1, o2) -> Long.compare(o2.getValue().getStake2(), o1.getValue().getStake2()));
            response.getWriter().println("Top 20 stake accounts:\n");
            for (int i = 0; i < 20; i++) {
                response.getWriter().println(list.get(i).getKey() + ": " + list.get(i).getValue().getStake2());
            }

            list.sort((o1, o2) -> Long.compare(
                    o2.getValue().getUnstake() + o2.getValue().getUnstake2(),
                    o1.getValue().getUnstake() + o1.getValue().getUnstake2()));
            response.getWriter().println("\nTop 20 unstake accounts:\n");
            for (int i = 0; i < 20; i++) {
                response.getWriter().println(list.get(i).getKey() + ": " +
                        list.get(i).getValue().getUnstake() + ", " +
                        list.get(i).getValue().getUnstake2());
            }

        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
