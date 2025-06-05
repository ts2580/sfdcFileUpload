package com.sfdcupload.file.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExcelFile {
    private String sfid;
    private Double attcFileId;
    private Double notificationParent;
    private String bbsAttachFileName;
    private Double bbsFileSize;
    private String bbsFileExt;
    private byte[] appendFile;
    private String createdObjectType;
    private String createdObjectId;
    private String createdProgramId;
    private LocalDateTime creationTimestamp;
    private String lastUpdatedObjectType;
    private String lastUpdatedObjectId;
    private String lastUpdateProgramId;
    private LocalDateTime lastUpdateTimestamp;
    private String dataEndStatus;
    private String dataEndObjectType;
    private String dataEndObjectId;
    private String dataEndProgramId;
    private LocalDateTime dataEndTimestamp;
    private String archiveCompletedFlag;
    private String archivedEmployeeNum;
    private LocalDateTime archivedTimestamp;
    private String archiveProgramId;
    private Integer isMig;
    private String id;
    private Integer seqId;
    private String contentId;
}