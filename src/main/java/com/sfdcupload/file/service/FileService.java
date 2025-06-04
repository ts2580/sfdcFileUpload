package com.sfdcupload.file.service;

import com.sfdcupload.file.dto.ExcelFile;

import java.util.List;

public interface FileService {
    int totalCafe();
    List<ExcelFile> findCafe();
    int updateCafe(List<ExcelFile> listCafe);

    int totalExport();
    List<ExcelFile> findExport();
    int updateExport(List<ExcelFile> listCert);

    int totalClaim();
    List<ExcelFile> findClaim();
    int updateClaim(List<ExcelFile> listClaim);
}