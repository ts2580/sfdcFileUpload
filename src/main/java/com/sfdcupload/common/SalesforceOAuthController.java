package com.sfdcupload.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

@RestController
@RequiredArgsConstructor
public class SalesforceOAuthController {

    private final OkHttpClient client = new OkHttpClient();

    @Value("${salesforce.clientId}")
    private String clientId;

    @Value("${salesforce.clientSecret}")
    private String clientSecret;

    @Value("${salesforce.redirectUri}")
    private String redirectUri;

    @Value("${salesforce.authUrl}")
    private String authUrl;

    @Value("${salesforce.tokenUrl}")
    private String tokenUrl;

    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        String redirect = authUrl +
                "?response_type=code" +
                "&client_id=" + clientId +
                "&redirect_uri=" + redirectUri;

        response.sendRedirect(redirect);
    }

    @GetMapping("/oauth/callback")
    public void callback(@RequestParam(required = false) String code, HttpSession session, HttpServletResponse response) throws IOException {
        if (code == null || code.isEmpty()) {
            return;
        }

        RequestBody requestBody = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("redirect_uri", redirectUri)
                .build();

        Request tokenRequest = new Request.Builder()
                .url(tokenUrl)
                .post(requestBody)
                .build();

        try (Response tokenResponse = client.newCall(tokenRequest).execute()) {
            if (!tokenResponse.isSuccessful()) {
                return;
            }

            String token = Objects.requireNonNull(tokenResponse.body()).string();

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(token);

            // ✅ 세션에 저장
            session.setAttribute("accessToken", rootNode.get("access_token").asText());

            response.sendRedirect("/?message=token_refreshed");
        }
    }

}