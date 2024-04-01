package org.tron.core.services.http.tracker;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
public abstract class BaseTrackerServlet extends RateLimiterServlet {

    @Autowired
    protected DynamicPropertiesStore dps;
    @Autowired
    protected ContractStateStore css;

    protected HttpServletRequest request;
    protected HttpServletResponse response;

    protected long cycleNumber;
    protected long cycleCount;

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        this.request = request;
        this.response = response;
        this.response.setContentType("application/json; charset=utf-8");

        this.cycleNumber =
                this.request.getParameter("cycle_number") == null ?
                        this.dps.getCurrentCycleNumber() : Long.parseLong(request.getParameter("cycle_number"));
        this.cycleNumber = Math.min(this.cycleNumber, this.dps.getCurrentCycleNumber());

        this.cycleCount =
                this.request.getParameter("cycle_count") == null ?
                        1 : Long.parseLong(this.request.getParameter("cycle_count"));

        try {
            responseGet();
        } catch (MissingParameterException e) {
            JSONObject res = new JSONObject();
            res.put("code", 500);
            res.put("error", e.getMessage());
            response.getWriter().println(res.toJSONString());
        }
    }

    protected String mustGetParameter(String name) {
        String value = this.request.getParameter(name);
        if (value == null) {
            throw new MissingParameterException(name);
        }
        return value;
    }

    protected String mayGetParameter(String name, String defaultValue) {
        String value = this.request.getParameter(name);
        return value == null ? defaultValue : value;
    }

    abstract void responseGet() throws IOException, MissingParameterException;
}
