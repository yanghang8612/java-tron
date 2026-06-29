package org.tron.core.services.http;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.Wallet;
import org.tron.core.store.AccountStore;
import org.tron.protos.Protocol;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Random;

@Component
@Slf4j(topic = "API")
public class GenerateAddressServlet extends RateLimiterServlet {

    final Random random = new Random();
//
//    @Autowired
//    private Wallet wallet;

    @Autowired
    private ChainBaseManager chainBaseManager;

    @SneakyThrows
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileWriter fileWriter = new FileWriter("accountAddress.txt");
        AccountStore accountStore = chainBaseManager.getAccountStore();
        accountStore.iterator().forEachRemaining(account -> {
            account.getValue();
            try {
                fileWriter.write(Hex.toHexString(account.getKey()) + '\n');
            } catch (IOException e) {
                logger.error("write file error ", e);
            }
        });
        fileWriter.close();
    }

}
