package org.tron.core.services.jsonrpc.interceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.jsonrpc4j.JsonRpcInterceptor;
import com.googlecode.jsonrpc4j.ProxyUtil;
import io.prometheus.client.Histogram;
import java.lang.reflect.Method;
import java.util.List;
import org.springframework.stereotype.Component;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;

@Component
public class MetricInterceptor implements JsonRpcInterceptor {

  private static final boolean FAST_SYNC_MODE = true;

  private final ThreadLocal<Histogram.Timer> timer = new ThreadLocal<>();

  @Override
  public void preHandleJson(JsonNode json) {

  }

  @Override
  public void preHandle(Object target, Method method, List<JsonNode> params) {
    if (FAST_SYNC_MODE) {
      return;
    }
    timer.set(Metrics.histogramStartTimer(MetricKeys.Histogram.JSONRPC_SERVICE_LATENCY,
        ProxyUtil.getMethodName(method)));
  }

  @Override
  public void postHandle(Object target, Method method, List<JsonNode> params, JsonNode result) {
    if (FAST_SYNC_MODE) {
      return;
    }
    try {
      Metrics.histogramObserve(timer.get());
    } finally {
      timer.remove();
    }
  }

  @Override
  public void postHandleJson(JsonNode json) {

  }
}
