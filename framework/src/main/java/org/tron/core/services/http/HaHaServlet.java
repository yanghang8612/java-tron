package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
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
        try {
          System.out.println("Counting thread started.");
          Stream<String> lines = Files.lines(Paths.get("/data/week.txt"));
          AtomicLong totalBurn = new AtomicLong();
          AtomicInteger count = new AtomicInteger();
          String finalCycleNumber = cycleNumber;
          Map<String, ContractStateCapsule> result = new HashMap<>();
          lines.forEach(l -> {
            byte[] addr = Commons.decodeFromBase58Check(l);
            addr[0] = (byte) 0x42;
            ContractStateCapsule csc = css.getWeekState(Long.parseLong(finalCycleNumber), addr);
            result.put(l, csc);
            totalBurn.getAndAdd(csc.getTrxBurn());

            if (count.getAndIncrement() % 1000 == 0) {
              System.out.printf("%d counted, total burn %d%n", count.get(), totalBurn.get());
            }
          });
          isCounting = false;
          System.out.println("Counting thread ended.");
          response.getWriter().printf("%d counted, total burn %d%n", count.get(), totalBurn.get());

          result.entrySet()
                  .stream()
                  .sorted((e1, e2) -> Long.compare(e2.getValue().getTrxBurn(), e1.getValue().getTrxBurn()))
                  .limit(100)
                  .forEach(e -> {
                    try {
                      response.getWriter().printf("%s %d%n", e.getKey(), e.getValue().getTrxBurn());
                    } catch (IOException e1) {
                      e1.printStackTrace();
                    }
                  });
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    } catch (Exception e) {
      Util.processError(e, response);
    }
  }
}
