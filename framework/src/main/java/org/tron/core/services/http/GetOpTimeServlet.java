package org.tron.core.services.http;

import org.tron.common.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.Wallet;
import org.tron.core.vm.VM;
import org.tron.protos.Protocol;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class GetOpTimeServlet extends RateLimiterServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    Protocol.Block nowBlock = wallet.getNowBlock();
    VM.opTimeRecords.get("blockNumber").put("end", nowBlock.getBlockHeader().getRawData().getNumber());
    String results = JsonUtil.obj2Json(VM.opTimeRecords);
    response.getWriter().println(results);
  }
}
