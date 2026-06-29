package org.tron.core.services.http;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
@Slf4j(topic = "API")
public class RunOpServlet extends OpServlet {

    @SneakyThrows
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        logger.info("run op begin ");
        parseConfig(request);

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");//设置日期格式
        String date = df.format(new Date());
        fileWriter.write(date + " run ops configFile: " + opConfig + "\n");
        fileWriter.write(String.format("round:%d\n", round));
        fileWriter.write(String.format("opName\tavgCost\tminCost\tmaxCost\tavg2\tremoveNum\trangeCount\n"));
        try {
            for (Object op : ops) {
                Map map = (Map) op;
                String opName = map.get("opName").toString();
                try {
                    logger.info("run op : " + opName);
                    byte[] bytecodes = getBytecodes(map);
                    String codeAddress = getCodeAddress(map);
                    List<String> stacks = getStacks(map);
                    List<String> memorys = getMemory(map);
                    cost = 0;
                    costList = new ArrayList<>();
                    runOp(bytecodes, codeAddress, stacks, memorys);
                    long avgCost = cost / round;
                    logger.info("run op : " + opName + " cost: " + avgCost);
                    String rangeInfo = countRange(avgCost);

                    //remove Big Value
                    long limit = avgCost * 10;
                    int count = 0;
                    long sum = 0;
                    for (long l : costList) {
                        if (l > limit) {
                            continue;
                        }
                        count += 1;
                        sum += l;
                    }
                    fileWriter.write(String.format("%s\t%d\t%d\t%d\t%d\t%d\t%s\n", opName, avgCost, minCost, maxCost, sum / count, round - count, rangeInfo));
                } catch (Exception e) {
                    // one op failing (e.g. opcode not activated on this chain) must not abort the batch
                    logger.warn("run op {} failed, skipped: {}", opName, e.toString());
                    fileWriter.write(String.format("%s\tERROR\t%s\n", opName, e.toString()));
                }
            }
            fileWriter.write("\n");
        }
        finally {
            fileWriter.close();
            ops = null;
            costList = null;
            addressList = null;
        }
    }

    private String countRange(long avgCost) {
        List<Long> divided = new ArrayList<>();
        for (int i = -5; i <= 5; i++) {
            long point = avgCost + avgCost * i / 10;
            divided.add(point);
        }

        int[] appearNums = new int[divided.size() + 1];
        for (Long l : costList) {
            int pos = 0;
            for (long point : divided) {
                if (l > point) {
                    pos = pos + 1;
                }
                else {
                    break;
                }
            }
            appearNums[pos] += 1;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < appearNums.length - 1; i++) {
            result.append(String.format("%d,", appearNums[i]));
        }
        result.append(String.format("%d", appearNums[appearNums.length - 1]));
        return result.toString();
    }

}
