package com.sfdcupload.file.repository;

import com.sfdcupload.file.dto.ExcelFile;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface FileRepository {

    int totalAccFile();
    List<ExcelFile> findAccFile();
    int updateAccFile(Map<String, Object> listFile);
}