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
        return fileRepository.totalAccFile();
    }

    @Override
    public List<ExcelFile> findCafe() {
        return fileRepository.findAccFile();
    }

    @Override
    public int updateCafe(List<ExcelFile> listCafe){

        Map<String, Object> mapCafe = new HashMap<>();
        mapCafe.put("listCafe", listCafe);

        return fileRepository.updateAccFile(mapCafe);
    }


}