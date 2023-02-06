package org.tron.common.utils;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class NetUtil {

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
