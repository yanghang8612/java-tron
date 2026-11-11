package org.tron.core.services.http.tracker;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.tron.core.ChainBaseManager;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.TrackerStore;
import org.tron.json.JSONObject;

public class ListCycleServletTest {

  @Test
  public void listsThirtyOneDaysWithoutHistoricalHeaders() throws Exception {
    ChainBaseManager manager = mock(ChainBaseManager.class);
    DynamicPropertiesStore properties = mock(DynamicPropertiesStore.class);
    TrackerStore tracker = mock(TrackerStore.class);
    when(manager.getDynamicPropertiesStore()).thenReturn(properties);
    when(manager.getTrackerStore()).thenReturn(tracker);
    when(properties.getCurrentCycleNumber()).thenReturn(1003L);
    when(tracker.getCycleEndTimestamp(anyLong())).thenAnswer(invocation -> {
      long cycle = invocation.getArgument(0);
      return 1_600_000_000_000L + cycle * 21_600_000L;
    });
    StringWriter output = new StringWriter();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(output));
    try (MockedStatic<ChainBaseManager> singleton = mockStatic(ChainBaseManager.class)) {
      singleton.when(ChainBaseManager::getInstance).thenReturn(manager);
      new ListCycleServlet().doGet(mock(HttpServletRequest.class), response);
    }
    JSONObject result = JSONObject.parseObject(output.toString());
    Assert.assertEquals(1003, result.getLongValue("current"));
    Assert.assertEquals(33, result.size());
    verify(manager, never()).getBlockByNum(anyLong());
  }
}
