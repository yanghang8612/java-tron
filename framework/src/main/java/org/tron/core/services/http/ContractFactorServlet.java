package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
import org.tron.core.store.ContractStateStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


@Component
@Slf4j(topic = "API")
public class ContractFactorServlet extends RateLimiterServlet {

  @Autowired
  private ContractStateStore css;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      css.forEach(v -> {
        try {
          if (v.getKey().length == 21 && v.getKey()[0] == 0x41 && v.getValue().getEnergyFactor() != 0) {
            response.getWriter().println(StringUtil.encode58Check(v.getKey()) + " " + v.getValue().getEnergyFactor());
          }
        } catch (Exception e) {
          Util.processError(e, response);
        }
      });
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) { }
}
