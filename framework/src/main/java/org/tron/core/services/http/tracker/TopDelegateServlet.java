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
public class TopDelegateServlet extends BaseTrackerServlet {

    @Override
    void responseGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<ByteString, ContractStateCapsule> data = css.getMergedDataWithinCycles(cycleNumber, cycleCount, false);
        List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(data.entrySet());
        list = list.stream()
                .filter(e -> e.getValue().getDelegatedAmount() != 0)
                .collect(Collectors.toList());

        list.sort((o1, o2) -> Long.compare(o2.getValue().getDelegatedAmount(), o1.getValue().getDelegatedAmount()));
        response.getWriter().println("Top 20 delegate accounts:\n");
        for (int i = 0; i < 20 && i < list.size(); i++) {
            response.getWriter().printf("%s: %d%n",
                    StringUtil.encode58Check(list.get(i).getKey().toByteArray()),
                    list.get(i).getValue().getDelegatedAmount());
        }
    }
}
