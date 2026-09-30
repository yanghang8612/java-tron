package org.tron.core.services.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.tron.api.GrpcAPI.TransactionExtention;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.vm.program.Program.OutOfTimeException;
import org.tron.json.JSONObject;

public class TriggerConstantContractServletTest extends BaseHttpTest {

  private TriggerConstantContractServlet servlet;

  @Override
  protected void setUpMocks() throws Exception {
    servlet = new TriggerConstantContractServlet();
    injectWallet(servlet);
  }

  @Test
  public void executionTimeIsReturnedForSuccessAndTimeout() throws Exception {
    String owner = ByteArray.toHexString(new ECKey().getAddress());
    String contract = ByteArray.toHexString(new ECKey().getAddress());
    String body = "{\"owner_address\":\"" + owner + "\",\"contract_address\":\""
        + contract + "\",\"data\":\"00\"}";
    when(wallet.createTransactionCapsule(any(), any()))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
    when(wallet.triggerConstantContract(any(), any(), any(), any())).thenAnswer(invocation -> {
      TransactionExtention.Builder builder = invocation.getArgument(2);
      builder.setExecutionTimeUs(1234);
      return MINIMAL_TX;
    });
    MockHttpServletResponse success = newResponse();
    servlet.doPost(postRequest(body), success);
    JSONObject successJson = JSONObject.parseObject(success.getContentAsString());
    assertEquals(1234L, successJson.getLongValue("execution_time_us"));
    assertTrue(Boolean.TRUE.equals(successJson.getJSONObject("result").getBoolean("result")));

    doAnswer(invocation -> {
      TransactionExtention.Builder builder = invocation.getArgument(2);
      builder.setExecutionTimeUs(10001);
      throw new OutOfTimeException("CPU timeout for test");
    }).when(wallet).triggerConstantContract(any(), any(), any(), any());
    MockHttpServletResponse failure = newResponse();
    servlet.doPost(postRequest(body), failure);
    JSONObject failureJson = JSONObject.parseObject(failure.getContentAsString());
    assertEquals(10001L, failureJson.getLongValue("execution_time_us"));
    assertFalse(Boolean.TRUE.equals(failureJson.getJSONObject("result").getBoolean("result")));
  }

  @Test
  public void zeroExecutionTimeRetainsJsonPresence() {
    TransactionExtention response = TransactionExtention.newBuilder()
        .setExecutionTimeUs(0).build();
    JSONObject json = JSONObject.parseObject(Util.printTransactionExtention(response, false));
    assertTrue(json.containsKey("execution_time_us"));
    assertEquals(0L, json.getLongValue("execution_time_us"));
  }

  @Test
  public void testManyFlatFieldsDoesNotOverflowStack() throws Exception {
    String owner = ByteArray.toHexString(new ECKey().getAddress());
    String contract = ByteArray.toHexString(new ECKey().getAddress());

    StringBuilder body = new StringBuilder(256 * 1024)
        .append("{\"owner_address\":\"").append(owner).append('"')
        .append(",\"contract_address\":\"").append(contract).append('"')
        .append(",\"data\":\"00\"");
    for (int i = 0; i < 20_000; i++) {
      body.append(",\"x").append(i).append("\":1");
    }
    body.append('}');

    when(wallet.createTransactionCapsule(any(), any()))
        .thenReturn(new TransactionCapsule(MINIMAL_TX));
    when(wallet.triggerConstantContract(any(), any(), any(), any()))
        .thenReturn(MINIMAL_TX);

    MockHttpServletResponse response = newResponse();
    servlet.doPost(postRequest(body.toString()), response);

    assertEquals(200, response.getStatus());
    verify(wallet).triggerConstantContract(any(), any(), any(), any());
  }
}
