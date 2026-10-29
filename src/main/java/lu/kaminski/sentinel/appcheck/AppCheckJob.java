package lu.kaminski.sentinel.appcheck;

import lu.kaminski.sentinel.evaluation.CheckStateService;
import lu.kaminski.sentinel.evaluation.StateTransition;
import lu.kaminski.sentinel.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
@RequiredArgsConstructor
public class AppCheckJob {

    private final AppCheckService     appCheckService;
    private final CheckStateService   checkStateService;
    private final NotificationService notificationService;
    private final Clock               clock;

    private final ReentrantLock lock = new ReentrantLock();

    @Scheduled(fixedDelayString = "#{@sentinelProperties.evaluation.appCheckInterval.toMillis()}")
    public void run() {
        if (!lock.tryLock()) {
            log.info("App check déjà en cours, ignoré");
            return;
        }
        try {
            String now = Instant.now(clock).toString();
            List<StateTransition> transitions = checkStateService.evaluate(
                    appCheckService.checkAll(), now);
            if (!transitions.isEmpty()) {
                notificationService.enqueue(transitions);
            }
        } finally {
            lock.unlock();
        }
    }
}
