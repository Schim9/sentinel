package lu.kaminski.sentinel.report;

import lu.kaminski.sentinel.domain.Host;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface HostRepository extends JpaRepository<Host, String> {

    @Query("SELECT h FROM Host h WHERE h.lastReportAt < :before OR h.lastReportAt IS NULL")
    List<Host> findSilentHosts(String before);

    @Modifying
    @Transactional
    @Query("UPDATE Host h SET h.state = :state WHERE h.name = :name")
    void updateState(String name, String state);
}
