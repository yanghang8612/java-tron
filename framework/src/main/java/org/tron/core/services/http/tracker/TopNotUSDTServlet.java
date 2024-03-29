package org.tron.core.services.http.tracker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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

@Component
@Slf4j(topic = "API")
public class TopNotUSDTServlet extends BaseTrackerServlet {

    @Override
    void responseGet() throws IOException {
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

        JSONObject res = new JSONObject();
        JSONArray top20Increased = new JSONArray();
        for (int i = 0; i < 20 && i < list.size(); i++) {
            JSONObject obj = new JSONObject();
            obj.put("address", StringUtil.encode58Check(list.get(i).getKey().toByteArray()));
            obj.put("num_of_changes_in_tx", list.get(i).getValue().getTxTotalCount());
            top20Increased.add(obj);
        }
        res.put("top_20_increased", top20Increased);

        JSONArray top20Decreased = new JSONArray();
        for (int i = 0; i < 20 && i < list.size(); i++) {
            int idx = list.size() - 1 - i;
            JSONObject obj = new JSONObject();
            obj.put("address", StringUtil.encode58Check(list.get(idx).getKey().toByteArray()));
            obj.put("num_of_changes_in_tx", list.get(idx).getValue().getTxTotalCount());
            top20Decreased.add(obj);
        }
        res.put("top_20_decreased", top20Decreased);

        response.getWriter().println(res.toJSONString());
    }
}
