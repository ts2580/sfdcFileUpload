package com.sfdcupload.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sfdcupload.file.dto.ExcelFile;
import okhttp3.*;
import okio.ByteString;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class SalesforceFileUpload {

    private final String myDomain = "https://yuricompany-dev-ed.develop.my.salesforce.com";

    public boolean uploadFileViaConnectAPI(byte[] fileByte, String fileName, String recordId, String accessToken) throws Exception {
        /* ConnectAPI MultiPart의 특징 */
        // 2GB 까지 업로드 가능
        // base64로 인코딩할 필요 없음
        // 하지만 시간당 2000 call 넘어가면 에러

        // OkHttp로 변경
        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(600, TimeUnit.SECONDS)       // 전체 호출 시간 제한
                .connectTimeout(15, TimeUnit.SECONDS)     // 연결 제한
                .writeTimeout(560, TimeUnit.SECONDS)      // 업로드(쓰기) 제한
                .readTimeout(30, TimeUnit.SECONDS)        // 응답(읽기) 제한
                .build();

        // OKHttp의 Multi Part 용 바디
        RequestBody requestBody = RequestBody.create(
                ByteString.of(fileByte),
                MediaType.parse("application/octet-stream")
        );

        // Post로 쏠 MultiPart 생성
        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        multipartBuilder.addFormDataPart("title", fileName);
        multipartBuilder.addFormDataPart("fileData", fileName, requestBody);

        // OKHttp Request
        Request uploadRequest = new Request.Builder()
                .url(myDomain + "/services/data/v63.0/connect/files/users/me")
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(multipartBuilder.build())
                .build();

        // try-with-resource : 자동으로 Try 문이 끝나면 연결 종료. 자원관리해줌
        try (Response response = client.newCall(uploadRequest).execute()) {

            int statusCode = response.code();

            // Entity 받아서 문자로 바꿔줌
            String responseBody = Objects.requireNonNull(response.body()).string(); // response.getEntity()가 Input Stream을 반환

            if (statusCode != 201 && statusCode != 200) {
                System.out.println("업로드 에러 :: " + responseBody);

                throw new Exception("파일 업로드 실패 ! ==> " + responseBody);
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(responseBody); // 셀포의 Map<String, Object>랑 동일함
            JsonNode idNode = rootNode.get("id");

            if (idNode == null) throw new Exception("파일 업로드 응답에 ID가 없음.");

            // 세일즈포스의 ContentDocument의 Id
            String fileId = idNode.asText();

            // ContentDocumentLink 만들기 위함
            Map<String, String> mapContent = new HashMap<>();
            mapContent.put("ContentDocumentId", fileId); // 파일 Id
            mapContent.put("LinkedEntityId", recordId); // 연결할 레코드 Id
            mapContent.put("ShareType", "V"); // View 권한, 수정권한은 C, 레코드의 권한 따라가는건 I


            // Request Body 생성하기
            RequestBody jsonRequestBody = generateRequestBody(mapContent, mapper);

            // Multi Part의 경우 MultiPartBuilder에서 Content Type 지정해주면 되지만 대부분은 Header에 Content Type 지정
            Request linkRequest = new Request.Builder()
                    .url(myDomain + "/services/data/v63.0/sobjects/ContentDocumentLink/")
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .post(jsonRequestBody)
                    .build();

            // httpClient는 재사용함
            try (Response linkResponse = client.newCall(linkRequest).execute()) {
                int linkStatusCode = linkResponse.code();

                if (linkStatusCode != 201 && linkStatusCode != 200) {
                    System.out.println("파일은 올렸지만 연결 실패 ==> " + Objects.requireNonNull(linkResponse.body()).string());

                    return false;
                }
            }

            return true;

        }
    }

    private RequestBody generateRequestBody(Object object, ObjectMapper mapper) throws JsonProcessingException {
        String jsonBody = mapper.writeValueAsString(object);
        return RequestBody.create(jsonBody, MediaType.parse("application/json"));
    }

    public boolean uploadFileViaContentVersionAPI(byte[] fileBytes, String fileName, String recordId, String accessToken) throws Exception {

        // 기존 API 할당량 먹음
        // 50MB 까지. 근데 base64로 인코딩 해야함
        // 인코딩 시 용량이 33% 늘어남. 대략 원본 용량 35MB 까지 가능
        // 제한사항도 동일
        // batch로 보낼 수 있...나?

        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(600, TimeUnit.SECONDS)       // 전체 호출 시간 제한
                .connectTimeout(15, TimeUnit.SECONDS)     // 연결 제한
                .writeTimeout(560, TimeUnit.SECONDS)      // 업로드(쓰기) 제한
                .readTimeout(30, TimeUnit.SECONDS)        // 응답(읽기) 제한
                .build();
        ObjectMapper mapper = new ObjectMapper();

        String contentVersionUrl = myDomain + "/services/data/v63.0/sobjects/ContentVersion";

        // 1. ContentVersion 업로드
        String base64Encoded = Base64.getEncoder().encodeToString(fileBytes);

        Map<String, String> contentVersionMap = new HashMap<>();
        contentVersionMap.put("Title", fileName);
        contentVersionMap.put("PathOnClient", fileName);
        contentVersionMap.put("VersionData", base64Encoded);

        RequestBody requestBody = generateRequestBody(contentVersionMap, mapper);

        Request request = new Request.Builder()
                .url(contentVersionUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        String contentDocumentId;

        try (Response response = client.newCall(request).execute()) {
            int statusCode = response.code();
            String responseBody = Objects.requireNonNull(response.body()).string();

            if (statusCode != 201 && statusCode != 200) {
                throw new RuntimeException("ContentVersion 업로드 실패: " + responseBody);
            }

            JsonNode rootNode = mapper.readTree(responseBody);
            String contentVersionId = rootNode.get("id").asText();

            // 2. ContentDocumentId 조회. 왜냐, 던져주는값은 LatestPublishedVersionId 인데, 레코드에 지정 하려면 ContentDocumentId가 필요하기 때문 ㅡㅡ
            // 쿼리 인코딩
            String strSOQL = URLEncoder.encode("SELECT ContentDocumentId FROM ContentVersion WHERE Id = '" + contentVersionId + "'", StandardCharsets.UTF_8);

            String getContentVersionUrl = myDomain + "/services/data/v63.0/query?q=" + strSOQL;

            Request queryRequest = new Request.Builder()
                    .url(getContentVersionUrl)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            try (Response queryResponse = client.newCall(queryRequest).execute()) {
                String getResponseBody = Objects.requireNonNull(queryResponse.body()).string();

                // 쿼리는 200번
                if (queryResponse.code() != 200) {
                    System.out.println("쿼리 실패: " + getResponseBody);
                    throw new RuntimeException("쿼리 실패: " + getResponseBody);
                }
                JsonNode getResult = mapper.readTree(getResponseBody);
                contentDocumentId = getResult.get("records").get(0).get("ContentDocumentId").asText();
            }
        }

        // 3. ContentDocumentLink로 레코드에 연결
        Map<String, String> linkMap = new HashMap<>();
        linkMap.put("ContentDocumentId", contentDocumentId);
        linkMap.put("LinkedEntityId", recordId);
        linkMap.put("ShareType", "V");

        RequestBody linkRequestBody = generateRequestBody(linkMap, mapper);

        Request linkRequest = new Request.Builder()
                .url(myDomain + "/services/data/v63.0/sobjects/ContentDocumentLink")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .post(linkRequestBody)
                .build();

        try (Response linkResponse = client.newCall(linkRequest).execute()) {
            int linkStatus = linkResponse.code();
            if (linkStatus != 201 && linkStatus != 200) {
                String error = Objects.requireNonNull(linkResponse.body()).string();
                throw new RuntimeException("ContentDocumentLink 연결 실패: " + error);
            }
        }

        return true;
    }

    private String trimTrailingComma(StringBuilder sb) {
        String s = sb.toString();
        if (s.endsWith(",")) return s.substring(0, s.length() - 1);
        return s;
    }

    public List<ExcelFile> uploadFileBatch(List<ExcelFile> listExcel, String accessToken) throws IOException {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .callTimeout(600, TimeUnit.SECONDS)       // 전체 호출 시간 제한
                .connectTimeout(15, TimeUnit.SECONDS)     // 연결 제한
                .writeTimeout(560, TimeUnit.SECONDS)      // 업로드(쓰기) 제한
                .readTimeout(30, TimeUnit.SECONDS)        // 응답(읽기) 제한
                .build();
        ObjectMapper mapper = new ObjectMapper();

        String connectBatchUrl = myDomain + "/services/data/v63.0/connect/batch";
        // 파일 업로드까지 성공한 List
        List<ExcelFile> listSuccess = new ArrayList<>();
        // ContentDocumentLink 생성 성공한 찐 성공 List
        List<ExcelFile> listRealSuccess = new ArrayList<>();

        ObjectNode root = mapper.createObjectNode();
        root.put("haltOnError", false);

        ArrayNode batchRequests = root.putArray("batchRequests");
        for (ExcelFile excelFile : listExcel) {
            // base64를 문자열로 말아야함.
            String base64Blob = Base64.getEncoder().encodeToString(excelFile.getAppendFile());

            // sobjects/ContentVersiondml richInput에 들어갈 JSON
            ObjectNode cvRequest = mapper.createObjectNode();
            cvRequest.put("Title", excelFile.getBbsAttachFileName());
            cvRequest.put("PathOnClient", excelFile.getBbsAttachFileName());
            cvRequest.put("VersionData", base64Blob);

            ObjectNode batchRequest = mapper.createObjectNode();
            batchRequest.put("url", "/services/data/v63.0/sobjects/ContentVersion");
            batchRequest.put("method", "POST");
            // Body에 들어갈 데이터
            batchRequest.put("richInput", cvRequest);

            batchRequests.add(batchRequest);
        }

        RequestBody requestBody = generateRequestBody(root, mapper);

        Request batchRequest = new Request.Builder()
                .url(connectBatchUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        StringBuilder cvIdsBuilder = new StringBuilder();

        try (Response batchResponse = okHttpClient.newCall(batchRequest).execute()) {

            String responseBody =  Objects.requireNonNull(batchResponse.body()).string();
            int batchStatus = batchResponse.code();
            if (batchStatus != 201 && batchStatus != 200) {
                System.out.println("batch 업로드 실패: " + responseBody);
                throw new RuntimeException("batch 업로드 실패: " + responseBody);
            }

            // JsonNode는 다형성을 가짐. 지금 읽어온 results는 Array임.
            JsonNode results = mapper.readTree(responseBody).get("results");
            for (int i = 0; i < results.size(); i++) {

                JsonNode result = results.get(i).get("result");
                if (result.get("success").asBoolean()) {
                    // ContentVersion Id
                    String contentVersionId = result.get("id").asText();
                    cvIdsBuilder.append("'").append(contentVersionId).append("',");

                    listExcel.get(i).setContentId(contentVersionId);
                    // 다행히 순서를 지키면서 와서 listExcel의 i번째를 가져오면 된다.
                    listSuccess.add(listExcel.get(i));
                }
            }
        }

        // SOQL 날릴 IN 절 String으로 구성
        String contentVersionIdSOQL = trimTrailingComma(cvIdsBuilder);
        // Key: ContentVersion Id, Value: ContentDocument Id
        Map<String, String> mapVersionIdToDocId = new HashMap<>();

        String soqlUrl = myDomain + "/services/data/v63.0/query?q=";
        String soql = URLEncoder.encode("SELECT ContentDocumentId FROM ContentVersion WHERE Id IN (" + contentVersionIdSOQL + ")", StandardCharsets.UTF_8);

        Request soqlRequest = new Request.Builder()
                .url(soqlUrl+soql)
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();
        try (Response soqlResponse = okHttpClient.newCall(soqlRequest).execute()) {
            String soqlResponseBody =  Objects.requireNonNull(soqlResponse.body()).string();
            int soqlStatus = soqlResponse.code();
            if (soqlStatus != 200) {
                System.out.println("SOQL 실패:: " + soqlResponseBody);

                throw new RuntimeException("SOQL 실패: " + soqlResponseBody);
            }

            JsonNode records = mapper.readTree(soqlResponseBody).get("records");

            for (JsonNode record : records) {
                String url = record.get("attributes").get("url").asText();
                String versionId = url.substring(url.lastIndexOf("/") + 1);
                String documentId = record.get("ContentDocumentId").asText();

                mapVersionIdToDocId.put(versionId, documentId);
            }
        }

        ObjectNode linkRoot = mapper.createObjectNode();
        linkRoot.put("haltOnError", false);

        ArrayNode linkArrayNode = linkRoot.putArray("batchRequests");
        for (ExcelFile success : listSuccess) {
            ObjectNode body = mapper.createObjectNode();
            body.put("ContentDocumentId", mapVersionIdToDocId.get(success.getContentId()));
            body.put("LinkedEntityId", success.getSfid());
            body.put("ShareType", "V");

            ObjectNode linkRequest = mapper.createObjectNode();
            linkRequest.put("url", "/services/data/v63.0/sobjects/ContentDocumentLink");
            linkRequest.put("method", "POST");
            linkRequest.put("richInput", body);

            linkArrayNode.add(linkRequest);
        }

        batchRequest = new Request.Builder()
                .url(connectBatchUrl)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Content-Type", "application/json")
                .post(generateRequestBody(linkRoot, mapper))
                .build();

        try (Response response = okHttpClient.newCall(batchRequest).execute()) {
            String linkResponseBody =  Objects.requireNonNull(response.body()).string();
            int linkStatus = response.code();
            if (linkStatus != 201 && linkStatus != 200) {
                System.out.println("Link Batch 실패: " + linkResponseBody);

                throw new RuntimeException("Link Batch 실패: " + linkResponseBody);
            }

            JsonNode results = mapper.readTree(linkResponseBody).get("results");
            for (int i = 0; i < results.size(); i++) {
                JsonNode result = results.get(i).get("result");
                if (result.get("success").asBoolean()) {
                    listSuccess.get(i).setIsMig(1);
                    listRealSuccess.add(listSuccess.get(i));
                }
            }
        }

        return listRealSuccess;
    }

}