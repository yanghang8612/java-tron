package org.tron.core.services.http.tracker;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.db2.common.WrappedByteArray;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

@Component
@Slf4j(topic = "API")
public class TopTRC10Servlet extends BaseTrackerServlet {

  @Override
  void responseGet() throws IOException {
    Map<String, Long> result = new HashMap<>();
    for (int i = 0; i < cycleCount; i++) {
      byte[] prefix = ((cycleNumber + i) + "-").getBytes();
      byte[] key = new byte[prefix.length + 1];
      System.arraycopy(prefix, 0, key, 0, prefix.length);
      key[prefix.length] = 0x51;
      Map<WrappedByteArray, ContractStateCapsule> data = css.prefixQuery(key);
      data.forEach((k, v) -> {
        String tokeName = new String(Arrays.copyOfRange(k.getBytes(), key.length, k.getBytes().length));
        result.put(tokeName, result.getOrDefault(tokeName, 0L) + v.getTxTrc10Count());
      });
    }

    List<Map.Entry<String, Long>> list = new LinkedList<>(result.entrySet());
    list.sort((o1, o2) -> Long.compare(o2.getValue(), o1.getValue()));

    JSONArray res = new JSONArray();
    for (int i = 0; i < 20 && i < list.size(); i++) {
      JSONObject obj = new JSONObject();
      obj.put("token_name", list.get(i).getKey());
      obj.put("tx_count", list.get(i).getValue());
      res.add(obj);
    }

    response.getWriter().println(res.toJSONString());
  }
}
