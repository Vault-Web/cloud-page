package cloudpage.repository;

import cloudpage.model.File;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileRepository extends PagingAndSortingRepository<File, Long> {
    // Define custom queries here (if needed)
}

