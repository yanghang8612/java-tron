package org.tron.core.services.http;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
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

        Map<ByteString, ContractStateCapsule> today =
                css.getMergedDataWithinCycles(cycleNumber, cycleCount, true);

        Map<ByteString, ContractStateCapsule> yesterday =
                css.getMergedDataWithinCycles(cycleNumber - cycleCount, cycleCount, true);

        yesterday.forEach((k, v) -> {
            if (today.containsKey(k)) {
                today.get(k).addTxTotalCount(-v.getTxTotalCount());
            } else {
                today.put(k, v);
                today.get(k).addTxTotalCount(-2 * v.getTxTotalCount());
            }
        });

        List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(today.entrySet());
        list.sort((o1, o2) -> Long.compare(o2.getValue().getTxTotalCount(), o1.getValue().getTxTotalCount()));

        try {
            response.getWriter().println("Top 20 increased contracts:\n");
            for (int i = 0; i < 20; i++) {
                response.getWriter().println(StringUtil.encode58Check(list.get(i).getKey().toByteArray()) +
                        ": " + list.get(i).getValue().getTxTotalCount());
            }
            response.getWriter().println("\nTop 20 decreased contracts:\n");
            for (int i = 0; i < 20; i++) {
                int idx = list.size() - 1 - i;
                response.getWriter().println(StringUtil.encode58Check(list.get(idx).getKey().toByteArray()) +
                        ": " + list.get(idx).getValue().getTxTotalCount());
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
