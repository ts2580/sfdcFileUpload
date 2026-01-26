package com.sfdcupload.file.controller;

import com.sfdcupload.common.SalesforceFileUpload;
import com.sfdcupload.file.dto.ExcelFile;
import com.sfdcupload.file.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;


@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final SalesforceFileUpload salesforceFileUpload;

    // long을 쓰는 이유: int는 크기가 너무 작음. int는 21억bit. 대략 2GB
    final long MAX_SIZE_BYTES = 35_000_000L; // 최대 35MB
    final long MAX_TOTAL_BATCH_SIZE = 6_000_000L; // 최대 6MB

    // File 저장할 EFS Mount 경로
    private static final Path EFS_ROOT = Paths.get("/mnt/efs/sfdc");

    // 관통 테스트 용 메소드
    @GetMapping("/ping")
    public String ping() {
        return "pong!";
    }

    // 파일을 받기 위한 메소드. Byte Stream을 받아서 파일로 저장
    @PutMapping("/uploadEFS")
    public ResponseEntity<String> uploadFile(HttpServletRequest request,
                                             @RequestHeader("X-Filename") String encodedFileName) {
        try {
            // 디렉터리 없으면 생성
            Files.createDirectories(EFS_ROOT);
            System.out.println("encodedFileName :: " + encodedFileName);
            // 헤더에서 파일명 복원
            String fileName = java.net.URLDecoder.decode(
                    encodedFileName,
                    java.nio.charset.StandardCharsets.UTF_8
            );
            // 디렉터리 traversal 방지
            String safeFileName = Paths.get(fileName).getFileName().toString();
            Path targetPath = EFS_ROOT.resolve(safeFileName);
            // 스트림을 그대로 파일에 복사 (용량 제한 없음)
            try (InputStream in = request.getInputStream();
                 OutputStream out = Files.newOutputStream(
                         targetPath,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING
                 )) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
            return ResponseEntity.ok("OK");
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("File upload failed.");
        }
    }

    @GetMapping("/upload")
    public SseEmitter upload(@RequestParam String dataId, @RequestParam int cycle, HttpSession session) {
        SseEmitter emitter = new SseEmitter(0L);
        String accessToken = (String) session.getAttribute("accessToken");

        new Thread(() -> {
            try {
                if (accessToken == null) {
                    emitter.send(SseEmitter.event().data("❌ 로그인되지 않았습니다."));
                    emitter.complete();
                    return;
                }


                int targetCnt = 0;
                int totalProcessed = 0;
                int loopCount = 0;
                int updateCnt = 0;

                targetCnt = fileService.totalAccFile();

                emitter.send(SseEmitter.event().data("progress: 0" + "," + totalProcessed + "," + targetCnt));

                while (true) {

                    // isMig가 0인 애들만 찾기
                    List<ExcelFile> listExcelFile = fileService.findAccFile();

                    if (listExcelFile.isEmpty()) {
                        emitter.send(SseEmitter.event().data("✅ 더 이상 처리할 항목이 없습니다"));
                        break;
                    }

                    // 성공한거 담아주는 List
                    List<ExcelFile> listSuccessAll = new ArrayList<>();

                    // 1. Connect/files/users/userId 로 보낼 Excel List
                    List<ExcelFile> listBig = new ArrayList<>();
                    // 2. Content Version 단건으로 보낼 Excel List
                    List<ExcelFile> listMedium = new ArrayList<>();
                    // 3. Connect/batch (다건) 로 보낼 다차원 배치 List
                    List<List<ExcelFile>> listSmallPrime = new ArrayList<>();
                    List<ExcelFile> listSmall = new ArrayList<>();

                    long currentBatchSize = 0;

                    for (ExcelFile excelFile : listExcelFile) {
                        // 현재 들어온 파일 사이즈
                        long fileSize = excelFile.getAppendFile().length;

                        if (fileSize > MAX_SIZE_BYTES) { // [1]
                            listBig.add(excelFile);
                        } else if (fileSize > MAX_TOTAL_BATCH_SIZE) { // [2]
                            listMedium.add(excelFile);
                        } else { // [3]
                            if (currentBatchSize + fileSize < MAX_TOTAL_BATCH_SIZE && listSmall.size() < 25) {
                                listSmall.add(excelFile);
                                currentBatchSize += fileSize;
                            } else {
                                if (listSmall.size() == 1) {
                                    // 단 1건이면 굳이 배치로 보내지 말고 ContentVersion 단건으로 넘기자
                                    listMedium.add(listSmall.get(0));
                                } else if (!listSmall.isEmpty()) {
                                    // 깊은 복사
                                    listSmallPrime.add(new ArrayList<>(listSmall));
                                }

                                listSmall.clear();
                                // Clear 된 listSmall 에 else 들어온 excelFile 담아준다. 여기서 안넣어주면 안들어가잖아
                                listSmall.add(excelFile);
                                // 들어온 대상 파일사이즈. 0아님
                                currentBatchSize = fileSize;
                            }
                        }
                    }

                    // 배치 사이즈가 25 여서 6/6/6/6/1 일 경우 남은 1을 담은 List<ExcelFile>을 넣어준다.
                    if (!listSmall.isEmpty()) {
                        if (listSmall.size() == 1) {
                            listMedium.add(listSmall.get(0));
                        } else {
                            listSmallPrime.add(new ArrayList<>(listSmall));
                        }
                    }

                    if (!listBig.isEmpty()) {
                        try {
                            for (ExcelFile excelFile : listBig) {
                                boolean result = salesforceFileUpload.uploadFileViaConnectAPI(
                                    excelFile.getAppendFile(), excelFile.getBbsAttachFileName(), excelFile.getSfid(), accessToken
                                );

                                if (result) {
                                    excelFile.setIsMig(1);
                                    listSuccessAll.add(excelFile);
                                    emitter.send(SseEmitter.event().data("✅ Connect Upload 성공, sfid :: " + excelFile.getSfid()));
                                } else {
                                    emitter.send(SseEmitter.event().data("‼️Connect Upload 실패!!"));
                                }
                            }
                        } catch (Exception e) {
                            emitter.send(SseEmitter.event().data("❌ 오류 :: " + e.getMessage()));
                        }
                    }

                    if (!listMedium.isEmpty()) {
                        try {
                            for (ExcelFile excelFile : listMedium) {
                                boolean result = salesforceFileUpload.uploadFileViaContentVersionAPI(
                                        excelFile.getAppendFile(), excelFile.getBbsAttachFileName(), excelFile.getSfid(), accessToken
                                );

                                if (result) {
                                    excelFile.setIsMig(1);
                                    listSuccessAll.add(excelFile);
                                    emitter.send(SseEmitter.event().data("✅ ContentVersionAPI 단건 성공, sfid :: " + excelFile.getSfid()));
                                } else {
                                    emitter.send(SseEmitter.event().data("❌ ContentVersionAPI 실패!!"));
                                }
                            }
                        } catch (Exception e) {
                            emitter.send(SseEmitter.event().data("❌ 오류 :: " + e.getMessage()));
                        }
                    }

                    if (!listSmallPrime.isEmpty()) {
                        for (List<ExcelFile> excelFiles : listSmallPrime) {
                            try {
                                List<ExcelFile> listSuccess = salesforceFileUpload.uploadFileBatch(
                                        excelFiles, accessToken
                                );

                                if (!listSuccess.isEmpty()) {
                                    // 성공한 것만 담아주기
                                    listSuccessAll.addAll(listSuccess);

                                    emitter.send(SseEmitter.event().data("✅ ContentVersionAPI Batch " + listSuccess.size() + "건 성공 "));
                                } else {
                                    emitter.send(SseEmitter.event().data("❌ Batch 실패"));
                                }
                            } catch (Exception e) {
                                emitter.send(SseEmitter.event().data("❌ 오류 : " + e.getMessage()));
                            }
                        }
                    }


                    if (!listSuccessAll.isEmpty()) {
                        updateCnt = fileService.updateAccFile(listSuccessAll);
                        totalProcessed += updateCnt;
                        emitter.send(SseEmitter.event().data("✔ " + updateCnt + "건 DB 반영 완료"));

                        double percent = (totalProcessed * 100.0) / targetCnt;
                        emitter.send(SseEmitter.event().data("progress:" + percent + "," + totalProcessed + "," + targetCnt));
                    }

                    loopCount++;
                    if (loopCount >= cycle) break;
                }

                emitter.send(SseEmitter.event().data("🎉 전체 완료 : 총 " + totalProcessed + "건 처리"));


            } catch (Exception e){

                try {
                    emitter.send(SseEmitter.event().data("❌ 처리 중 예외 발생 : " + e.getMessage()));
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            } finally {
                emitter.complete();
            }


        }).start();

        return emitter;
    }
}
