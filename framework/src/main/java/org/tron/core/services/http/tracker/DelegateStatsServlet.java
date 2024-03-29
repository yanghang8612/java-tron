package org.tron.core.services.http.tracker;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Commons;
import org.tron.core.capsule.ContractStateCapsule;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Slf4j(topic = "API")
public class DelegateStatsServlet extends BaseTrackerServlet {

    @Override
    void responseGet() throws IOException, MissingParameterException {
        String address = mustGetParameter("address");
        byte[] ownerAddr = Commons.decode58Check(address);
        ContractStateCapsule owner = css.getIntervalData(cycleNumber, cycleCount, ownerAddr, false);
        ContractStateCapsule result = new ContractStateCapsule(0);
        for (String acc : owner.getDelegatedAccounts()) {
            byte[] addr = Commons.decode58Check(acc);
            ContractStateCapsule consumer = css.getIntervalData(cycleNumber, cycleCount, addr);
            result.addEnergyUsage(consumer.getEnergyUsage());
            result.addTrxBurn(consumer.getTrxBurn());
        }

        JSONObject res = new JSONObject();
        res.put("address", address);
        res.put("energy_usage", result.getEnergyUsage());
        res.put("trx_burn", result.getTrxBurn());
        response.getWriter().println(res.toJSONString());
    }
}
