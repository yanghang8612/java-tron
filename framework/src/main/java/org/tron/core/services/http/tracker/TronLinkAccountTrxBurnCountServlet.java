package org.tron.core.services.http.tracker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.capsule.ContractStateCapsule;

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
public class TronLinkAccountTrxBurnCountServlet extends BaseTrackerServlet {

  private boolean isCounting;

  @Override
  void responseGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!isCounting) {
      isCounting = true;
      System.out.println("Counting thread started.");
      Stream<String> lines = Files.lines(Paths.get("/data/week.txt"));
      AtomicLong totalBurn = new AtomicLong();
      AtomicInteger count = new AtomicInteger();
      Map<String, ContractStateCapsule> result = new HashMap<>();
      lines.forEach(l -> {
        try {
          byte[] addr = Commons.decodeFromBase58Check(l);
          addr[0] = (byte) 0x42;
          ContractStateCapsule csc = css.getWeekState(cycleNumber, addr);
          result.put(l, csc);
          totalBurn.getAndAdd(csc.getTrxBurn());

          if (count.getAndIncrement() % 1000 == 0) {
            System.out.printf("%d counted, total burn %d%n", count.get(), totalBurn.get());
          }
        } catch (Exception e) {
          System.out.println(e.getMessage());
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
    }
  }
}
