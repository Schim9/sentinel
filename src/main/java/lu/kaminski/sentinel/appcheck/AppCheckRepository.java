package lu.kaminski.sentinel.appcheck;

import lu.kaminski.sentinel.domain.AppCheckRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AppCheckRepository extends JpaRepository<AppCheckRecord, Long> {

    @Query("SELECT a FROM AppCheckRecord a WHERE a.app = :app ORDER BY a.checkedAt DESC LIMIT 1")
    java.util.Optional<AppCheckRecord> findLatest(String app);

    @Query("SELECT a FROM AppCheckRecord a ORDER BY a.checkedAt DESC LIMIT 1")
    java.util.Optional<AppCheckRecord> findLatestOverall();

    @Modifying
    @Transactional
    @Query("DELETE FROM AppCheckRecord a WHERE a.checkedAt < :before")
    int deleteByCheckedAtBefore(String before);
}
