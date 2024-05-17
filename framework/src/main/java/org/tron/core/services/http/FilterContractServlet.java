package org.tron.core.services.http;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.store.AccountStore;
import org.tron.protos.Protocol;

@Component
@Slf4j(topic = "API")
public class FilterContractServlet extends RateLimiterServlet {

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String fileName =
        request.getParameter("file_name") == null
            ? null
            : String.valueOf(request.getParameter("file_name"));
    if (StringUtils.isEmpty(fileName)) {
      response.getWriter().println("Failed: Empty file name!");
    } else {
      File file = new File(fileName + ".txt");
      if (!file.exists()) {
        response.getWriter().println("Failed: File not exists!");
      } else {
        AccountStore accountStore = ChainBaseManager.getInstance().getAccountStore();

        String filteredFile = fileName + "-filtered.txt";
        PrintWriter writer = new PrintWriter(filteredFile);
        String address;
        long count = 0;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        while ((address = reader.readLine()) != null) {
          AccountCapsule accountCapsule = accountStore.get(Commons.decodeFromBase58Check(address));
          if (Objects.nonNull(accountCapsule)
              && accountCapsule.getInstance().getType().equals(Protocol.AccountType.Contract)) {
            continue;
          }
          count++;
          writer.println(address);
        }
        writer.close();

        response
            .getWriter()
            .println("Success: \nFile name: " + filteredFile + "\nAddress count: " + count);
      }
    }
  }
}
