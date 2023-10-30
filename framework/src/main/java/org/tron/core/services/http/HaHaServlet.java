package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.ChainBaseManager;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
@Slf4j(topic = "API")
public class HaHaServlet extends RateLimiterServlet {

  private boolean isCounting;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
      ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();

      String cycleNumber = request.getParameter("cycle_number");
      if (cycleNumber == null) {
        cycleNumber = String.valueOf(dps.getCurrentCycleNumber());
      }
//      response.getWriter().println("Current cycle number: " + dps.getCurrentCycleNumber());
//      response.getWriter().println("Query cycle number: " + cycleNumber + "\n");

      if (!isCounting) {
        isCounting = true;
        String finalCycleNumber = cycleNumber;
        new Thread(() -> {
          try {
            System.out.println("Counting thread started.");
            Stream<String> lines = Files.lines(Paths.get("/data/week.txt"));
            AtomicLong totalBurn = new AtomicLong();
            AtomicInteger count = new AtomicInteger();
            lines.forEach(l -> {
              totalBurn.getAndAdd(css.getWeekState(Long.parseLong(finalCycleNumber), Commons.decodeFromBase58Check(l)).getTrxBurn());

              if (count.getAndIncrement() % 1000 == 0) {
                System.out.printf("%d counted, total burn %d%n", count.get(), totalBurn.get());
              }
            });
            isCounting = false;
            System.out.println("Counting thread ended.");
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }).start();
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
