package org.tron.core.services.http;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class PreOpServlet extends OpServlet {

    private long lastCost = Long.MAX_VALUE;

    @SneakyThrows
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        logger.info("pre op begin ");
        parseConfig(request);

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");//设置日期格式
        String date = df.format(new Date());
        fileWriter.write(date + " pre ops configFile" + opConfig + "\n");
        fileWriter.write(String.format("round:%d\n", round));
        try {
            for (Object op : ops) {
                Map map = (Map) op;
                String opName = map.get("opName").toString();
                logger.info("pre op : " + opName);

                byte[] bytecodes = getBytecodes(map);
                String codeAddress = getCodeAddress(map);
                List<String> stacks = getStacks(map);
                List<String> memory = getMemory(map);
                lastCost = Long.MAX_VALUE;
                while (true) {
                    cost = 0;
                    runOp(bytecodes, codeAddress, stacks, memory);
                    long avgCost = cost / round;
                    fileWriter.write(String.format("%s\t%d\n", opName, avgCost));
                    if (lastCost < cost) {
                        break;
                    }
                    lastCost = cost;
                }
                fileWriter.write("\n");
            }
        }
        finally {
            fileWriter.close();
            ops = null;
            lastCost = Long.MAX_VALUE;
            addressList = null;
        }
    }

}
