package cloudpage.repository;

import org.hibernate.cache.spi.support.AbstractReadWriteAccess.Item;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import cloudpage.model.File;

@Repository
public interface FileRepository extends JpaRepository<File, Long>{

    @Query("select f from File f")
    Page<File> findAllFiles(Pageable pageable);

    @Query("select fi from File fi where fi.name like %:path%")
    Page<File> searchAllFiles(Pageable pageable, String path);

}
