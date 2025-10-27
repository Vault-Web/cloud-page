package cloudpage.service;

import cloudpage.model.File;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FileEntityService {
    Page<File> getAllFiles(Pageable pageable);
}
