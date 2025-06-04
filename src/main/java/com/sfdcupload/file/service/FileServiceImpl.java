package com.sfdcupload.file.service;

import com.sfdcupload.file.dto.ExcelFile;
import com.sfdcupload.file.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    private final FileRepository fileRepository;

    public int totalCafe(){
        return fileRepository.totalCafe();
    }

    @Override
    public List<ExcelFile> findCafe() {
        return fileRepository.findCafe();
    }

    @Override
    public int updateCafe(List<ExcelFile> listCafe){

        Map<String, Object> mapCafe = new HashMap<>();
        mapCafe.put("listCafe", listCafe);

        return fileRepository.updateCafe(mapCafe);
    }

    public int totalExport(){
        return fileRepository.totalExport();
    }

    @Override
    public List<ExcelFile> findExport() {
        return fileRepository.findExport();
    }

    @Override
    public int updateExport(List<ExcelFile> listCert){

        Map<String, Object> mapExport = new HashMap<>();
        mapExport.put("listCert", listCert);

        return fileRepository.updateExport(mapExport);
    }

    public int totalClaim(){
        return fileRepository.totalClaim();
    }

    @Override
    public List<ExcelFile> findClaim() {
        return fileRepository.findClaim();
    }

    @Override
    public int updateClaim(List<ExcelFile> listClaim){

        Map<String, Object> mapCafe = new HashMap<>();
        mapCafe.put("listClaim", listClaim);

        return fileRepository.updateClaim(mapCafe);
    }
}