package com.sfdcupload.file.repository;

import com.sfdcupload.file.dto.ExcelFile;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface FileRepository {

    int totalCafe();
    List<ExcelFile> findCafe();
    int updateCafe(Map<String, Object> listCafe);

    int totalExport();
    List<ExcelFile> findExport();
    int updateExport(Map<String, Object> listCert);

    int totalClaim();
    List<ExcelFile> findClaim();
    int updateClaim(Map<String, Object> listClaim);
}