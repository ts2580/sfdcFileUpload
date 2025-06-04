package com.sfdcupload.common;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Value;

import java.awt.*;
import java.net.URI;

public class SalesforceOAuth {

    @Value("${salesforce.clientId}")
    private static String clientId;

    @Value("${salesforce.clientSecret}")
    private static String clientSecret;

    @Value("${salesforce.userName}")
    private static String userName;

    @Value("${salesforce.passWord}")
    private static String passWord;

    @Value("${salesforce.tokenUrl}")
    private static String tokenUrl;

    @Value("${salesforce.redirectUri}")
    private static String redirectUri;


    public static String getAccessToken() throws Exception {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost post = new HttpPost(tokenUrl);
        StringEntity params = new StringEntity(
                "grant_type=password&client_id=" + clientId +
                        "&client_secret=" + clientSecret +
                        "&username=" + userName +
                        "&password=" + passWord);
        post.addHeader("content-type", "application/x-www-form-urlencoded");
        post.setEntity(params);

        CloseableHttpResponse response = httpClient.execute(post);
        String responseString = EntityUtils.toString(response.getEntity());
        JSONParser parser = new JSONParser();
        JSONObject json = (JSONObject) parser.parse(responseString);
        return String.valueOf(json.get("access_token"));
    }

    public static void OpenSalesforceAuthPage() throws Exception {

        try {
            String clientId = "YOUR_CLIENT_ID";
            String authUrl = "https://posco--partial.sandbox.my.salesforce-setup.com/services/oauth2/authorize" +
                    "?response_type=code" +
                    "&client_id=" + clientId +
                    "&redirect_uri=" + redirectUri;

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(authUrl));
                System.out.println("브라우저에서 Salesforce 로그인 창이 열렸습니다.");
            } else {
                System.err.println("Desktop API를 사용할 수 없습니다.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}