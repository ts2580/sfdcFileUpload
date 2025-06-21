package com.sfdcupload.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.json.Json;
import com.sfdcupload.file.dto.ExcelFile;
import okhttp3.*;
import okio.ByteString;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SpringBootApplication
public class SalesforceFileUpload {

    private final String myDomain = "https://yuricompany-dev-ed.develop.my.salesforce.com";

    public boolean uploadFileViaConnectAPI(byte[] fileByte, String fileName, String recordId, String accessToken) throws Exception {
        /* ConnectAPI MultiPart의 특징 */
        // 2GB 까지 업로드 가능
        // base64로 인코딩할 필요 없음
        // 하지만 시간당 2000 call 넘어가면 에러

        // OkHttp로 변경
        OkHttpClient client = new OkHttpClient();

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

            if (statusCode != 201) {
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

                if (linkStatusCode != 201) {
                    System.out.println("파일은 올렸지만 연결 실패 ==> " + Objects.requireNonNull(linkResponse.body()).string());

                    return false;
                }
            }

            return true;

        }
    }

    private RequestBody generateRequestBody(Map<String, String> mapContent, ObjectMapper mapper) throws JsonProcessingException {
        String jsonBody = mapper.writeValueAsString(mapContent);
        return RequestBody.create(jsonBody, MediaType.parse("application/json"));
    }

    public boolean uploadFileViaContentVersionAPI(byte[] fileBytes, String fileName, String recordId, String accessToken) throws Exception {

        // 기존 API 할당량 먹음
        // 50MB 까지. 근데 base64로 인코딩 해야함
        // 인코딩 시 용량이 33% 늘어남. 대략 원본 용량 35MB 까지 가능
        // 제한사항도 동일
        // batch로 보냉 수 있...나?


        OkHttpClient client = new OkHttpClient();
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

            if (statusCode != 201) {
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
            if (linkStatus != 201) {
                String error = Objects.requireNonNull(linkResponse.body()).string();
                throw new RuntimeException("ContentDocumentLink 연결 실패: " + error);
            }
        }

        return true;
    }

    public List<ExcelFile> uploadFileBatch(List<ExcelFile> listExcel, String accessToken) throws IOException {

        String connectBatchUrl = myDomain + "/services/data/v63.0/connect/batch";

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();

        // Jackson으로 JSON 구성
        ObjectMapper mapper = new ObjectMapper();

        ObjectNode root = mapper.createObjectNode();

        // 에러시 멈춤
        root.put("haltOnError", false);

        ArrayNode batchRequests = root.putArray("batchRequests");

        List<ExcelFile> successList = new ArrayList<>();                    // 파일 업로드 성공
        List<ExcelFile> successRealList = new ArrayList<>();                // sfdc 재지정까지 성공

        String base64Encoded;
        StringBuilder lpvIdsBuilder = new StringBuilder();
        for (ExcelFile file : listExcel) {

            ObjectNode req = mapper.createObjectNode();
            req.put("url", "/services/data/v63.0/sobjects/ContentVersion");
            req.put("method", "POST");

            base64Encoded = Base64.getEncoder().encodeToString(file.getAppendFile());

            ObjectNode body = mapper.createObjectNode();

            body.put("Title", file.getBbsAttachFileName());
            body.put("PathOnClient", file.getBbsAttachFileName());
            body.put("VersionData", base64Encoded);

            req.set("richInput", body);

            batchRequests.add(req);
        }

        String jsonBody = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        // multipart 구성
        builder.addPart("json", new StringBody(jsonBody, ContentType.APPLICATION_JSON));

        HttpEntity multipart = builder.build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        multipart.writeTo(out);

        // HTTP POST 실행
        HttpPost post = new HttpPost(connectBatchUrl);
        post.setHeader("Authorization", "Bearer " + accessToken);
        post.setHeader("Accept", "application/json");
        post.setEntity(multipart);

        try (CloseableHttpClient client = HttpClients.createDefault(); CloseableHttpResponse response = client.execute(post)) {

            HttpEntity responseEntity = response.getEntity();

            int status = response.getStatusLine().getStatusCode();

            if (status != 201 && status != 200) {
                String responseBody = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
                System.out.println("파일 업로드 응답 본문: " + responseBody);
                throw new RuntimeException("파일 업로드 실패 : " + response.getStatusLine().getReasonPhrase());
            }

            String responseBody = EntityUtils.toString(response.getEntity());
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(responseBody);

            JsonNode results = rootNode.get("results");
            JsonNode result;

            // 리턴값으로
            for (int idx = 0; idx < results.size(); idx++) {
                result = results.get(idx).get("result");

                if(result.get("success").asBoolean()){
                    lpvIdsBuilder.append("'").append(result.get("id").asText()).append("',");
                    listExcel.get(idx).setContentId(result.get("id").asText());                     // 일단 LatestPublishedVersionId 넣어줌
                    successList.add(listExcel.get(idx));
                }
            }
        }

        String rawIdList = lpvIdsBuilder.toString();
        if (rawIdList.endsWith(",")) {
            rawIdList = rawIdList.substring(0, rawIdList.length() - 1);
        }

        // 주는건 LatestPublishedVersionId인데 우리에게 필요한건 ContentDocumentId다. 미쳐버리겠네
        Map<String,String> mapPidToCId = new HashMap<>();

        String getContentVersionUrl = myDomain + "/services/data/v63.0/query?q=" +
                URLEncoder.encode("SELECT ContentDocumentId FROM ContentVersion WHERE Id IN (" + rawIdList + ")", StandardCharsets.UTF_8);

        HttpGet get = new HttpGet(getContentVersionUrl);
        get.setHeader("Authorization", "Bearer " + accessToken);

        ObjectMapper objectMapper = new ObjectMapper();
        try (CloseableHttpClient client = HttpClients.createDefault(); CloseableHttpResponse response = client.execute(get)) {
            String responseBody = EntityUtils.toString(response.getEntity());

            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode results = rootNode.get("records");

            for (JsonNode result : results) {
                String PublishedVersionIdPath = result.get("attributes").get("url").asText();
                String PublishedVersionId = PublishedVersionIdPath.substring(PublishedVersionIdPath.lastIndexOf("/") + 1);
                mapPidToCId.put(PublishedVersionId, result.get("ContentDocumentId").asText());
            }
        }


        // content Document에 넣는것 뿐만 아니라, 레코드에 재 지정하는것도 벌크로 하기!
        root = mapper.createObjectNode();

        root.put("haltOnError", false);

        batchRequests = root.putArray("batchRequests");

        for (ExcelFile file : successList) {

            ObjectNode req = mapper.createObjectNode();
            req.put("url", "/services/data/v63.0/sobjects/ContentDocumentLink/");
            req.put("method", "POST");

            ObjectNode body = mapper.createObjectNode();

            file.setContentId(mapPidToCId.get(file.getContentId())); // 갈아껴주자

            body.put("ContentDocumentId", file.getContentId());
            body.put("LinkedEntityId", file.getSfid());
            body.put("ShareType", "V");

            req.set("richInput", body);

            batchRequests.add(req);
        }

        jsonBody = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        // HTTP POST 실행. 이번엔
        post = new HttpPost(connectBatchUrl);
        post.setHeader("Authorization", "Bearer " + accessToken);
        post.setHeader("Accept", "application/json");
        post.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

        StringBuilder cdlBuilder = new StringBuilder();

        try (CloseableHttpClient client = HttpClients.createDefault(); CloseableHttpResponse response = client.execute(post)) {

            HttpEntity responseEntity = response.getEntity();

            int status = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);

            if (status != 201 && status != 200) {
                System.out.println("재지정 응답 본문: " + responseBody);
                throw new RuntimeException("파일 레코드 지정 실패 : " + response.getStatusLine().getReasonPhrase());
            }else{
                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode results = rootNode.get("results");

                // 아니 애는 ContentDocumentLink Id를 주네.진짜 미쳐버리겠네.
                // 파일 업로드랑 레코드 재 지정이랑 둘다 성공해야 ㄹㅇ 성공이니까 다시 모아
                for (JsonNode result : results) {
                    JsonNode innerResults = result.get("result");

                    if(innerResults.get("success").asBoolean()){
                        cdlBuilder.append("'").append(innerResults.get("id").asText()).append("',");
                    }
                }
            }
        }

        String rawIdLinkList = cdlBuilder.toString();
        if (rawIdLinkList.endsWith(",")) {
            rawIdLinkList = rawIdLinkList.substring(0, rawIdLinkList.length() - 1);
        }

        // 슈벌. ContentDocumentLink의 Id를 줘가지고...
        String getContentLinkUrl = myDomain + "/services/data/v63.0/query?q=" +
                URLEncoder.encode("SELECT Id, ContentDocumentId FROM ContentDocumentLink WHERE Id IN (" + rawIdLinkList + ") AND ShareType = 'V'", StandardCharsets.UTF_8);

        get = new HttpGet(getContentLinkUrl);
        get.setHeader("Authorization", "Bearer " + accessToken);

        objectMapper = new ObjectMapper();
        Set<String> setSuccessContentDocument = new HashSet<>();
        try (CloseableHttpClient client = HttpClients.createDefault(); CloseableHttpResponse response = client.execute(get)) {
            String responseBody = EntityUtils.toString(response.getEntity());

            JsonNode rootNode = objectMapper.readTree(responseBody);

            JsonNode records = rootNode.get("records");

            // ContentDocumentLink로 검색해서 나온 ContentDocumentId를 Set에 넣어주자. 애네가 진짜 레코드 재 지정까지 성공한거니까
            for (JsonNode record : records) {
                setSuccessContentDocument.add(record.get("ContentDocumentId").asText());
            }
        }

        // sfdc 파일 업로드 성공한 list(successList) 에서
        // 레코드에 재지정 성공한것(contains으로 거르기)만 모아 successRealList에 넣는다.
        for (ExcelFile excelFile : successList) {

            if(setSuccessContentDocument.contains(excelFile.getContentId())){
                excelFile.setIsMig(1);
                successRealList.add(excelFile);
            }
        }

        return successRealList;
    }

}