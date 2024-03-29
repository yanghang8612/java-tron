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
import java.util.stream.Collectors;

@Component
@Slf4j(topic = "API")
public class TopDelegateServlet extends BaseTrackerServlet {

    @Override
    void responseGet() throws IOException {
        Map<ByteString, ContractStateCapsule> data = css.getMergedDataWithinCycles(cycleNumber, cycleCount, false);
        List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(data.entrySet());
        list = list.stream()
                .filter(e -> e.getValue().getDelegatedAmount() != 0)
                .sorted((o1, o2) ->
                        Long.compare(o2.getValue().getDelegatedAmount(), o1.getValue().getDelegatedAmount()))
                .collect(Collectors.toList());

        JSONArray res = new JSONArray();
        for (int i = 0; i < 20 && i < list.size(); i++) {
            JSONObject obj = new JSONObject();
            obj.put("address", StringUtil.encode58Check(list.get(i).getKey().toByteArray()));
            obj.put("delegated_amount", list.get(i).getValue().getDelegatedAmount());
            res.add(obj);
        }
        response.getWriter().println(res.toJSONString());
    }
}
