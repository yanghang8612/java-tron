package org.tron.core.services.http;


import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.store.AccountStore;
import org.tron.core.store.StorageRowStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class GenerateStorageKeyServlet extends RateLimiterServlet{

    @Autowired
    private ChainBaseManager chainBaseManager;

    private int count;

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileWriter fileWriter = new FileWriter("storageKeys.txt");
        StorageRowStore storageRowStore = chainBaseManager.getStorageRowStore();
        for (Map.Entry<byte[], StorageRowCapsule> next : storageRowStore) {
            try {
                fileWriter.write(Hex.toHexString(next.getKey()) + '\n');
            } catch (IOException e) {
                logger.error("write file error ", e);
            }
            count++;
            if (count == 4000000) {
                break;
            }
        }
        fileWriter.close();
    }


}
