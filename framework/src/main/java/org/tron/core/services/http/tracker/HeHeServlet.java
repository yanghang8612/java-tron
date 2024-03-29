package org.tron.core.services.http.tracker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.ContractStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class HeHeServlet extends BaseTrackerServlet {

  @Override
  void responseGet() throws IOException {
    ContractStore cs = ChainBaseManager.getInstance().getContractStore();

    JSONObject resObj = new JSONObject();
    resObj.put("total", css.getIntervalData(cycleNumber, cycleCount, "total".getBytes()).toJsonObject());
    resObj.put("big", css.getIntervalData(cycleNumber, cycleCount, "big".getBytes()).toJsonObject());
    resObj.put("small", css.getIntervalData(cycleNumber, cycleCount, "small".getBytes()).toJsonObject());

    if (request.getParameter("address") == null) {
      Map<ByteString, ContractStateCapsule> result = css.getMergedDataWithinCycles(cycleNumber, cycleCount, true);
      List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(result.entrySet());

      String sortedBy = request.getParameter("sorted_by");
      if (sortedBy == null) {
        sortedBy = "totalUsage";
      }
      switch (sortedBy) {
        case "usage":
          list.sort((o1, o2) ->
                  Long.compare(o2.getValue().getEnergyUsage(), o1.getValue().getEnergyUsage()));
          break;
        case "totalPenalty":
          list.sort((o1, o2) ->
                  Long.compare(o2.getValue().getEnergyPenaltyTotal(), o1.getValue().getEnergyPenaltyTotal()));
          break;
        case "trxBurn":
          list.sort((o1, o2) ->
                  Long.compare(o2.getValue().getTrxBurn(), o1.getValue().getTrxBurn()));
          break;
        case "txCount":
          list.sort((o1, o2) ->
                  Long.compare(o2.getValue().getTxTotalCount(), o1.getValue().getTxTotalCount()));
          break;
        default:
          list.sort((o1, o2) ->
                  Long.compare(o2.getValue().getEnergyUsageTotal(), o1.getValue().getEnergyUsageTotal()));
      }

      JSONArray top100Array = new JSONArray();
      for (int i = 0; i < 100; i++) {
        JSONObject topObj = new JSONObject();
        topObj.put(StringUtil.encode58Check(list.get(i).getKey().toByteArray()),
                list.get(i).getValue().toJsonObject());
        top100Array.add(topObj);
      }
      resObj.put("top100", top100Array);
    } else {
      byte[] addr = Commons.decodeFromBase58Check(request.getParameter("address"));
      resObj.put(request.getParameter("address"),
              css.getIntervalData(cycleNumber, cycleCount, addr, false).toJsonObject());
    }

    response.getWriter().println(resObj.toJSONString());
  }
}
