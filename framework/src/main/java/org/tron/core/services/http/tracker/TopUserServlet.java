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
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.services.http.Util;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.ContractStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class TopUserServlet extends BaseTrackerServlet {

  @Override
  void responseGet() throws IOException {
    Map<ByteString, ContractStateCapsule> result = css.getMergedDataWithinCycles(cycleNumber, cycleCount, false);
    List<Map.Entry<ByteString, ContractStateCapsule>> list = new LinkedList<>(result.entrySet());
    list.sort((o1, o2) -> Long.compare(o2.getValue().getTrxBurn(), o1.getValue().getTrxBurn()));

    JSONArray res = new JSONArray();
    for (int i = 0; i < 10000; i++) {
      JSONObject obj = new JSONObject();
      obj.put("address", StringUtil.encode58Check(list.get(i).getKey().toByteArray()));
      obj.put("trx_burn", list.get(i).getValue().getTrxBurn());
      obj.put("tx_count", list.get(i).getValue().getTxCount());
      res.add(obj);
    }

    response.getWriter().println(res.toJSONString());
  }
}
