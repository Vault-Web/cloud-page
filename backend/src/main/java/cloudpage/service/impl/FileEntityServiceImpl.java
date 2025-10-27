package cloudpage.service.impl;

import cloudpage.model.File;
import cloudpage.repository.FileRepository;
import cloudpage.service.FileEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class FileEntityServiceImpl implements FileEntityService {

    @Autowired
    private FileRepository fileRepository;

    @Override
    public Page<File> getAllFiles(Pageable pageable) {
        return fileRepository.findAll(pageable);
    }
}
