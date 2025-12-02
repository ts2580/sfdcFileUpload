package com.sfdcupload.file.service;

import com.sfdcupload.file.dto.ExcelFile;

import java.util.List;

public interface FileService {
    int totalAccFile();
    List<ExcelFile> findAccFile();
    int updateAccFile(List<ExcelFile> listFile);
}