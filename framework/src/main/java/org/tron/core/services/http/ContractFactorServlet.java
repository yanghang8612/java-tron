package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.K;
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
      css.prefixQuery(new byte[]{0x41}).forEach((k, v) -> {
        try {
          if (k.getBytes().length == 21 && (v.getEnergyFactor() != 0 || v.getEnergyUsage() > 1_000_000_000L)) {
            response.getWriter().println(StringUtil.encode58Check(k.getBytes()) + " " + v.getEnergyUsage() + " " + v.getEnergyFactor());
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
