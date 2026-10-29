package lu.kaminski.sentinel.report;

import lu.kaminski.sentinel.domain.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {

    List<Report> findTop10ByHostOrderByReceivedAtDesc(String host);

    @Query("SELECT r FROM Report r WHERE r.host = :host ORDER BY r.receivedAt DESC LIMIT 1")
    java.util.Optional<Report> findLatestByHost(String host);

    @Modifying
    @Query("DELETE FROM Report r WHERE r.receivedAt < :before")
    int deleteByReceivedAtBefore(String before);

    @Query("SELECT AVG(r.cpuTempC), MIN(r.cpuTempC), MAX(r.cpuTempC), MAX(r.maxDiskPct), MAX(1.0 - r.memAvailablePct / 100.0) " +
           "FROM Report r WHERE r.host = :host AND r.receivedAt >= :since AND r.receivedAt < :until")
    Object[] aggregateForDay(String host, String since, String until);

    @Query("SELECT COUNT(r) FROM Report r WHERE r.host = :host AND r.receivedAt >= :since AND r.receivedAt < :until")
    long countByHostAndPeriod(String host, String since, String until);
}
