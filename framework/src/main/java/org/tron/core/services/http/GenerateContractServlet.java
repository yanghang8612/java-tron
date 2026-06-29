package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.store.ContractStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileWriter;
import java.io.IOException;

@Component
@Slf4j(topic = "api")
public class GenerateContractServlet extends RateLimiterServlet {

  @Autowired
  private ChainBaseManager chainBaseManager;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    FileWriter fileWriter = new FileWriter("contractAddress.txt");
    ContractStore contractStore = chainBaseManager.getContractStore();
    contractStore.getRevokingDB().iterator().forEachRemaining(entry -> {
      try {
        fileWriter.write(Hex.toHexString(entry.getKey()) + '\n');
      } catch (IOException e) {
        logger.error("write file error ", e);
      }
    });
    fileWriter.close();
  }

}
