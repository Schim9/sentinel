package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    boolean existsBySubjectKeyAndClosedAtIsNull(String subjectKey);

    List<Incident> findByClosedAtIsNullOrderByOpenedAtDesc();

    @Query("SELECT i FROM Incident i WHERE i.openedAt >= :since ORDER BY i.openedAt DESC")
    List<Incident> findRecentIncidents(String since);

    @Modifying
    @Transactional
    @Query("UPDATE Incident i SET i.closedAt = :now WHERE i.subjectKey = :subjectKey AND i.closedAt IS NULL")
    void closeOpenIncidents(String subjectKey, String now);

    @Modifying
    @Transactional
    @Query("DELETE FROM Incident i WHERE i.openedAt < :before AND i.closedAt IS NOT NULL")
    int deleteByOpenedAtBefore(String before);
}
