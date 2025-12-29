package com.sfdcupload.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sfdcupload.file.dto.ExcelFile;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import okio.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SalesforceFileUpload {

    @Value("${salesforce.myDomain}")
    private String myDomain;

    // 공용 ObjectMapper - 쓰레드 세이프 하므로 재사용한다.
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 공용 OkHttp
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .callTimeout(600, TimeUnit.SECONDS)       // 전체 호출 시간 제한
            .connectTimeout(15, TimeUnit.SECONDS)     // 연결 제한
            .writeTimeout(560, TimeUnit.SECONDS)      // 업로드(쓰기) 제한
            .readTimeout(30, TimeUnit.SECONDS)        // 응답(읽기) 제한
            .build();

    private RequestBody generateRequestBody(Object object) throws JsonProcessingException {
        String jsonBody = MAPPER.writeValueAsString(object);
        return RequestBody.create(jsonBody, MediaType.parse("application/json"));
    }


    public boolean uploadFileViaConnectAPI(byte[] fileByte, String fileName, String recordId, String accessToken) throws Exception {
        /* ConnectAPI MultiPart의 특징 */
        // 2GB 까지 업로드 가능
        // base64로 인코딩할 필요 없음
        // 하지만 시간당 2000 call 넘어가면 에러

        // OKHttp의 Multi Part 용 바디
        RequestBody fileBody  = RequestBody.create(
                ByteString.of(fileByte),
                MediaType.parse("application/octet-stream")
        );

        Map<String, String> contentVersionMap = new HashMap<>();
        contentVersionMap.put("firstPublishLocationId", recordId);

        RequestBody jsonPart = generateRequestBody(contentVersionMap);

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // fileData만 있으면 됨
                .addFormDataPart("fileData", fileName, fileBody)
                // Multipart 외 Json 부분은 따로 넣어줘야함
                .addPart(Headers.of("Content-Disposition", "form-data; name=\"json\""), jsonPart)
                .build();

        Request uploadRequest = new Request.Builder()
                .url(myDomain + "/services/data/v65.0/connect/files/users/me")
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(requestBody)
                .build();

        try (Response response = CLIENT.newCall(uploadRequest).execute()) {

            int statusCode = response.code();

            // Entity 받아서 문자로 바꿔줌
            String responseBody = Objects.requireNonNull(response.body()).string(); // response.getEntity()가 Input Stream을 반환

            if (statusCode != 201 && statusCode != 200) {
                System.out.println("업로드 에러 :: " + responseBody);

                throw new Exception("파일 업로드 실패 ! ==> " + responseBody);
            }

            return true;

        }
    }

    public boolean uploadFileViaContentVersionAPI(byte[] fileBytes, String fileName, String recordId, String accessToken) throws Exception {

        // 기존 API 할당량 먹음
        // 50MB 까지. 근데 base64로 인코딩 해야함
        // 인코딩 시 용량이 33% 늘어남. 대략 원본 용량 35MB 까지 가능
        // 제한사항도 동일
        // batch로 보낼 수 있...나?
        String contentVersionUrl = myDomain + "/services/data/v65.0/sobjects/ContentVersion";

        // ContentVersion 업로드
        String base64Encoded = Base64.getEncoder().encodeToString(fileBytes);

        Map<String, String> contentVersionMap = new HashMap<>();
        contentVersionMap.put("Title", fileName);
        contentVersionMap.put("PathOnClient", fileName);
        contentVersionMap.put("VersionData", base64Encoded);
        contentVersionMap.put("firstPublishLocationId", recordId);

        RequestBody requestBody = generateRequestBody(contentVersionMap);

        Request request = new Request.Builder()
                .url(contentVersionUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            int statusCode = response.code();
            String responseBody = Objects.requireNonNull(response.body()).string();

            if (statusCode != 201 && statusCode != 200) {
                System.out.println("ContentVersion 업로드 실패: " + responseBody);

                throw new Exception("ContentVersion 업로드 실패 ! ==> " + responseBody);
            }

            return true;
        }
    }

    public List<ExcelFile> uploadFileBatch(List<ExcelFile> listExcel, String accessToken) throws IOException {

        String connectBatchUrl = myDomain + "/services/data/v65.0/connect/batch";
        // 파일 업로드 성공한 List
        List<ExcelFile> listSuccess = new ArrayList<>();

        ObjectNode root = MAPPER.createObjectNode();
        root.put("haltOnError", false);

        ArrayNode batchRequests = root.putArray("batchRequests");
        for (ExcelFile excelFile : listExcel) {
            // base64를 문자열로 말아야함.
            String base64Blob = Base64.getEncoder().encodeToString(excelFile.getAppendFile());

            // sobjects/ContentVersion의 richInput에 들어갈 JSON
            ObjectNode cvRequest = MAPPER.createObjectNode();
            cvRequest.put("Title", excelFile.getBbsAttachFileName());
            cvRequest.put("PathOnClient", excelFile.getBbsAttachFileName());
            cvRequest.put("VersionData", base64Blob);
            cvRequest.put("firstPublishLocationId", excelFile.getSfid());

            ObjectNode batchRequest = MAPPER.createObjectNode();
            batchRequest.put("url", "/services/data/v65.0/sobjects/ContentVersion");
            batchRequest.put("method", "POST");
            // Body에 들어갈 데이터
            batchRequest.put("richInput", cvRequest);

            batchRequests.add(batchRequest);
        }

        RequestBody requestBody = generateRequestBody(root);

        Request batchRequest = new Request.Builder()
                .url(connectBatchUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        try (Response batchResponse = CLIENT.newCall(batchRequest).execute()) {

            String responseBody =  Objects.requireNonNull(batchResponse.body()).string();
            int batchStatus = batchResponse.code();
            if (batchStatus != 201 && batchStatus != 200) {
                System.out.println("batch 업로드 실패: " + responseBody);
                throw new RuntimeException("batch 업로드 실패: " + responseBody);
            }

            // JsonNode는 다형성을 가짐. 지금 읽어온 results는 Array임.
            JsonNode results = MAPPER.readTree(responseBody).get("results");
            for (int i = 0; i < results.size(); i++) {

                JsonNode result = results.get(i).get("result");
                if (result.get("success").asBoolean()) {
                    listExcel.get(i).setIsMig(1);
                    listSuccess.add(listExcel.get(i));
                }
            }
        }

        return listSuccess;
    }

}