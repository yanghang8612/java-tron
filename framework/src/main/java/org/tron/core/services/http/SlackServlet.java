package org.tron.core.services.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j(topic = "API")
public class SlackServlet extends RateLimiterServlet {

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {

  }
}
