package com.sfdcupload.file.controller;

import com.sfdcupload.common.SalesforceFileUpload;
import com.sfdcupload.file.dto.ExcelFile;
import com.sfdcupload.file.service.FileService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @GetMapping("/upload")
    public SseEmitter upload(@RequestParam String dataId, HttpSession session) {
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
                List<ExcelFile> listExcelFile;
                List<ExcelFile> listSuccessFile = new ArrayList<>();

                targetCnt = fileService.totalCafe();

                emitter.send(SseEmitter.event().data("progress: 0" + "," + totalProcessed + "," + targetCnt));

                while (true) {
                    // isMig가 0인 애들만 찾기
                    listExcelFile = fileService.findCafe();

                    if (listExcelFile.isEmpty()) {
                        emitter.send(SseEmitter.event().data("✅ 더 이상 처리할 항목이 없습니다"));
                        break;
                    }

                    for (ExcelFile excelFile : listExcelFile) {
                        try {
                            boolean result = new SalesforceFileUpload().uploadFileViaConnectAPI(
                                    excelFile.getAppendFile(), excelFile.getBbsAttachFileName(), excelFile.getSfid(), accessToken
                            );

                            if (result) {
                                excelFile.setIsMig(1);
                                // 성공한 것만 담아주기
                                listSuccessFile.add(excelFile);

                                emitter.send(SseEmitter.event().data("✅ ContentVersionAPI 단건 업로드 성공, id : " + excelFile.getSfid() + ", 파일명 : " + excelFile.getBbsAttachFileName()));
                            } else {
                                emitter.send(SseEmitter.event().data("❌ 업로드 실패, id : " + excelFile.getSfid()));
                            }
                        } catch (Exception e) {
                            emitter.send(SseEmitter.event().data("❌ 오류 : " + e.getMessage()));
                        }
                    }

                    if (!listSuccessFile.isEmpty()) {
                        updateCnt = fileService.updateCafe(listSuccessFile);
                        totalProcessed += updateCnt;
                        emitter.send(SseEmitter.event().data("✔ " + updateCnt + "건 DB 반영 완료"));

                        double percent = (totalProcessed * 100.0) / targetCnt;
                        emitter.send(SseEmitter.event().data("progress:" + percent + "," + totalProcessed + "," + targetCnt));
                    }
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