package org.tron.core.services.http.tracker;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.ContractStateCapsule;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j(topic = "API")
public class TopStakeServlet extends BaseTrackerServlet {

    @Override
    void responseGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<ByteString, ContractStateCapsule> data = css.getMergedDataWithinCycles(cycleNumber, cycleCount, false);
        List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(data.entrySet());
        list = list.stream()
                .filter(e -> e.getValue().getStake2() != 0 ||
                        e.getValue().getCancel() != 0 ||
                        e.getValue().getUnstake() != 0 ||
                        e.getValue().getUnstake2() != 0)
                .collect(Collectors.toList());

        list.sort((o1, o2) -> Long.compare(
                o2.getValue().getStake2() + o2.getValue().getCancel(),
                o1.getValue().getStake2() + o1.getValue().getCancel()));
        response.getWriter().println("Top 20 stake accounts:\n\n[addr: stake2, cancel, unstake, unstake2]");
        for (int i = 0; i < 20 && i < list.size(); i++) {
            response.getWriter().printf("%s: %d, %d, %d, %d%n",
                    StringUtil.encode58Check(list.get(i).getKey().toByteArray()),
                    list.get(i).getValue().getStake2(),
                    list.get(i).getValue().getCancel(),
                    list.get(i).getValue().getUnstake(),
                    list.get(i).getValue().getUnstake2());
        }

        list.sort((o1, o2) -> Long.compare(
                o2.getValue().getUnstake() + o2.getValue().getUnstake2(),
                o1.getValue().getUnstake() + o1.getValue().getUnstake2()));
        response.getWriter().println("\nTop 20 unstake accounts:\n\n[addr: stake2, cancel, unstake, unstake2]");
        for (int i = 0; i < 20 && i < list.size(); i++) {
            response.getWriter().printf("%s: %d, %d, %d, %d%n",
                    StringUtil.encode58Check(list.get(i).getKey().toByteArray()),
                    list.get(i).getValue().getStake2(),
                    list.get(i).getValue().getCancel(),
                    list.get(i).getValue().getUnstake(),
                    list.get(i).getValue().getUnstake2());
        }
    }
}
