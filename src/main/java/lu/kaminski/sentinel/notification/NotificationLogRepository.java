package lu.kaminski.sentinel.notification;

import lu.kaminski.sentinel.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    @Query("SELECT COUNT(n) FROM NotificationLog n WHERE n.channel = 'PUSH' AND n.sentAt >= :since AND n.success = true")
    long countSuccessfulPushesSince(String since);

    @Modifying
    @Query("DELETE FROM NotificationLog n WHERE n.sentAt < :before")
    int deleteByBefore(String before);
}
