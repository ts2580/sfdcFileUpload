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

    public int totalAccFile(){
        return fileRepository.totalAccFile();
    }

    @Override
    public List<ExcelFile> findAccFile() {
        return fileRepository.findAccFile();
    }

    @Override
    public int updateAccFile(List<ExcelFile> listFile){

        Map<String, Object> mapFile = new HashMap<>();
        mapFile.put("listFile", listFile);

        return fileRepository.updateAccFile(mapFile);
    }


}