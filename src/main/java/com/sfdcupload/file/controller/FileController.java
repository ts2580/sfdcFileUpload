package com.sfdcupload.file.controller;

import com.sfdcupload.common.SalesforceFileUpload;
import com.sfdcupload.file.dto.ExcelFile;
import com.sfdcupload.file.service.FileService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    final long MAX_SIZE_BYTES = 45_000_000L;
    final long MAX_TOTAL_BATCH_SIZE = 6_000_000L;

    @GetMapping("/upload")
    public SseEmitter getRecord(@RequestParam String dataId, HttpSession session) {
        SseEmitter emitter = new SseEmitter(0L);
        String accessToken = (String) session.getAttribute("accessToken");

        /* 정신차려보니 컨트롤러 너무 무거워졌는데, 언제 이렇게 코드 늘었냐.*/
        /*  todo 서비스로 빼기*/

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

                if(dataId.equals("cafe")){
                    targetCnt = fileService.totalCafe();
                } else if(dataId.equals("export")){
                    targetCnt = fileService.totalExport();
                } else if(dataId.equals("claim")){
                    targetCnt = fileService.totalClaim();
                }

                emitter.send(SseEmitter.event().data("progress: 0" + "," + totalProcessed + "," + targetCnt));

                while (true) {

                    if(dataId.equals("cafe")){
                        listExcelFile = fileService.findCafe();
                    } else if(dataId.equals("export")){
                        listExcelFile = fileService.findExport();
                    }else{
                        listExcelFile = fileService.findClaim();
                    }

                    if (listExcelFile.isEmpty()) {
                        emitter.send(SseEmitter.event().data("✅ 더 이상 처리할 항목이 없습니다"));
                        break;
                    }

                    List<ExcelFile> listBig = new ArrayList<>();                // 50MB 이상 파일 모음
                    List<List<ExcelFile>> listSmallMain = new ArrayList<>();
                    List<ExcelFile> listSmall = new ArrayList<>();              // 1 ~ 6 MB 파일 모음
                    List<ExcelFile> listMedium = new ArrayList<>();             // 6 ~ 50 MB 파일 모음
                    List<ExcelFile> successList = new ArrayList<>();

                    int currentBatchSize = 0;
                    int fileSize;
                    for (ExcelFile file : listExcelFile) {
                        fileSize = file.getAppendFile().length;
                        if(fileSize > MAX_SIZE_BYTES){
                            System.out.println("용량 큼. 이름 :: " + file.getBbsAttachFileName() + ", sfid :: " + file.getSfid());
                            listBig.add(file);
                        }else{
                            // 현재 리스트에 넣어도 10MB 넘지 않으면 추가
                            // 아니 connect/files API는 용량 상한이 2GB인데 배치를 못쳐
                            // ContentVersion API는 상한선이 50메가인데
                            // 이걸 모아서 connect/batch 로 치면 JSON 바디 상한이 10MB임. 이런 개똥같은
                            // 근데 Base64로 인코딩 하잖아! 그럼 또 슈벌 33% 늘어난다고 ㅡㅡ
                            // 결국 MAX_TOTAL_BATCH_SIZE는 6mb
                            // 이거 코드만 복잡해지고 실익이 있나
                            if(fileSize > MAX_TOTAL_BATCH_SIZE){
                                // 6 ~ 50MB는 개별 ContentVersion로 처리할것임.
                                listMedium.add(file);
                            }else if (currentBatchSize + fileSize <= MAX_TOTAL_BATCH_SIZE) {
                                // 6MB 이하다? 넣고 currentBatchSize 더해
                                listSmall.add(file);
                                currentBatchSize += fileSize;
                            } else {
                                // 넘는다? 지금까지 모인 listSmall을 listSmallMain에 추가하고
                                // currentBatchSize에 새로 넣은 fileSize 추가 하고 새로 시작
                                listSmallMain.add(new ArrayList<>(listSmall));
                                listSmall.clear();
                                listSmall.add(file);
                                currentBatchSize = fileSize;
                            }
                        }
                    }

                    // 마지막에 남은 파일들도 추가
                    if (!listSmall.isEmpty()) {
                        listSmallMain.add(new ArrayList<>(listSmall));
                    }

                    if(!listBig.isEmpty()){
                        for (ExcelFile file : listBig) {
                            try {
                                // 애는 1건씩 들어감.
                                boolean result = new SalesforceFileUpload().uploadFileViaConnectAPI(
                                        file.getAppendFile(),
                                        file.getBbsAttachFileName(),
                                        file.getSfid(),
                                        accessToken
                                );

                                if (result) {
                                    file.setIsMig(true);
                                    successList.add(file);
                                    emitter.send(SseEmitter.event().data("✅ Connect 업로드 성공, id : " + file.getSfid() + ", 파일명 : " + file.getBbsAttachFileName()));
                                } else {
                                    emitter.send(SseEmitter.event().data("❌ 업로드 실패, id : " + file.getSfid()));
                                }

                                Thread.sleep(200);
                            } catch (Exception e) {
                                emitter.send(SseEmitter.event().data("❌ 오류 : " + e.getMessage()));
                            }
                        }
                    }

                    if(!listMedium.isEmpty()){
                        for (ExcelFile file : listMedium) {
                            try {
                                boolean result = new SalesforceFileUpload().uploadFileViaContentVersionAPI(
                                        file.getAppendFile(),
                                        file.getBbsAttachFileName(),
                                        file.getSfid(),
                                        accessToken
                                );

                                if (result) {
                                    file.setIsMig(true);
                                    successList.add(file);
                                    emitter.send(SseEmitter.event().data("✅ ContentVersionAPI 단건 업로드 성공, id : " + file.getSfid() + ", 파일명 : " + file.getBbsAttachFileName()));
                                } else {
                                    emitter.send(SseEmitter.event().data("❌ 업로드 실패, id : " + file.getSfid()));
                                }
                            } catch (Exception e) {
                                emitter.send(SseEmitter.event().data("❌ 오류 : " + e.getMessage()));
                            }
                        }
                    }

                    if(!listSmallMain.isEmpty()){
                        // 10MB씩 끊어서 들어감 슈벌
                        System.out.println("배치로 들어감");
                        for (List<ExcelFile> listSmallExcelFile : listSmallMain) {
                            List<ExcelFile> listSmallSuccess = new SalesforceFileUpload().uploadFileBatch(listSmallExcelFile, accessToken);
                            if(!listSmallSuccess.isEmpty()){
                                emitter.send(SseEmitter.event().data("✅ ContentVersionAPI Batch 업로드 " + listSmallSuccess.size() + "건 성공"));
                                successList.addAll(listSmallSuccess);
                            }
                        }
                    }

                    if (!successList.isEmpty()) {

                        if(dataId.equals("cafe")){                              // ✅ 배치 업데이트
                            updateCnt = fileService.updateCafe(successList);
                        } else if(dataId.equals("export")){
                            updateCnt = fileService.updateExport(successList);
                        }else if(dataId.equals("claim")){
                            updateCnt = fileService.updateClaim(successList);
                        }

                        totalProcessed += updateCnt;
                        emitter.send(SseEmitter.event().data("✔ " + updateCnt + "건 DB 반영 완료"));

                        double percent = (totalProcessed * 100.0) / targetCnt;
                        emitter.send(SseEmitter.event().data("progress:" + percent + "," + totalProcessed + "," + targetCnt));
                    }

                    loopCount++;

                    // 일단 10개씩 50번만 돌림
                    // if (loopCount > 1) break;
                }

                emitter.send(SseEmitter.event().data("🎉 전체 완료 : 총 " + totalProcessed + "건 처리"));
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data("❌ 처리 중 예외 발생 : " + e.getMessage()));
                } catch (IOException ignored) {}
            } finally {
                emitter.complete();
            }
        }).start();

        return emitter;
    }



}