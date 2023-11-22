package org.tron.core.services.http.tracker;

import org.tron.core.ChainBaseManager;
import org.tron.core.services.http.RateLimiterServlet;
import org.tron.core.store.ContractStateStore;
import org.tron.core.store.DynamicPropertiesStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public abstract class BaseTrackerServlet extends RateLimiterServlet {

    protected DynamicPropertiesStore dps = ChainBaseManager.getInstance().getDynamicPropertiesStore();
    protected ContractStateStore css = ChainBaseManager.getInstance().getContractStateStore();
    protected long cycleNumber;
    protected long cycleCount;

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        cycleNumber = request.getParameter("cycle_number") == null ?
                dps.getCurrentCycleNumber() : Long.parseLong(request.getParameter("cycle_number"));
        cycleNumber = Math.min(cycleNumber, dps.getCurrentCycleNumber());
        cycleCount = request.getParameter("cycle_count") == null ?
                1 : Long.parseLong(request.getParameter("cycle_count"));
        responseGet(request, response);
    }

    abstract void responseGet(HttpServletRequest request, HttpServletResponse response) throws IOException;
}
