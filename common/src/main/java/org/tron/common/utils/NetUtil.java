package org.tron.common.utils;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NetUtil {

  private static final OkHttpClient client = new OkHttpClient();

  @Data
  private static class Price {
    int mins;
    String price;
  }

  public static double getPrice(String symbol) {
    for (int i = 0; i < 3; i++) {
      try {
        Price price = JsonUtil.json2Obj(
            get("https://api.binance.com/api/v3/avgPrice?symbol=" + symbol), Price.class
        );
        return Double.parseDouble(price.getPrice());
      } catch (Exception ignored) { }
    }
    return 0;
  }

  @Data
  private static class GasPrice {
    String status;
    String message;
    Result result;
  }

  @Data
  private static class Result {
    @JsonAlias("LastBlock")
    String lastBlock;
    @JsonAlias("SafeGasPrice")
    String safeGasPrice;
    @JsonAlias("ProposeGasPrice")
    String proposeGasPrice;
    @JsonAlias("FastGasPrice")
    String fastGasPrice;
    String suggestBaseFee;
    String gasUsedRatio;
  }

  public static int getGasPrice() {
    for (int i = 0; i < 3; i++) {
      try {
        GasPrice gasPrice = JsonUtil.json2Obj(
            get("https://api.etherscan.io/api?module=gastracker&action=gasoracle"
                + "&apikey=82SMH9HIUESXN4IPSFA237VHIMHQB1AQSI"), GasPrice.class
        );
        return Integer.parseInt(gasPrice.result.getProposeGasPrice());
      } catch (Exception e) { }
    }
    return 16;
  }

  public static String get(String url) {
    Request request = new Request.Builder()
        .url(url)
        .build();

    try (Response response = client.newCall(request).execute()) {
      return response.body().string();
    } catch (IOException e) { }
    return "";
  }

  public static String post(String targetURL, String jsonStr) {
    HttpURLConnection connection = null;

    try {
      //Create connection
      URL url = new URL(targetURL);
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      connection.setDoOutput(true);

      //Send request
      DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
      byte[] input = jsonStr.getBytes(StandardCharsets.UTF_8);
      wr.write(input, 0, input.length);
      wr.close();

      //Get Response
      InputStream is = connection.getInputStream();
      BufferedReader rd = new BufferedReader(new InputStreamReader(is));
      StringBuilder response = new StringBuilder(); // or StringBuffer if Java version 5+
      String line;
      while ((line = rd.readLine()) != null) {
        response.append(line);
        response.append('\r');
      }
      rd.close();
      return response.toString();
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
