package lu.kaminski.sentinel.retention;

import lu.kaminski.sentinel.ack.AccessTokenRepository;
import lu.kaminski.sentinel.appcheck.AppCheckRepository;
import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.evaluation.IncidentRepository;
import lu.kaminski.sentinel.notification.NotificationLogRepository;
import lu.kaminski.sentinel.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Job nocturne 03:30 — purge des données expirées.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RetentionJob {

    private final ReportRepository       reportRepository;
    private final AppCheckRepository     appCheckRepository;
    private final IncidentRepository     incidentRepository;
    private final NotificationLogRepository logRepository;
    private final AccessTokenRepository  tokenRepository;
    private final SentinelProperties     props;
    private final Clock                  clock;

    @Scheduled(cron = "0 30 3 * * *", zone = "#{@sentinelProperties.timezone}")
    @Transactional
    public void run() {
        Instant now = Instant.now(clock);
        String rawCutoff     = now.minus(props.getRetention().getRaw()).toString();
        String historyCutoff = now.minus(props.getRetention().getHistory()).toString();
        // access_token : 30 jours après expiration
        String tokenCutoff   = now.minus(props.getRetention().getRaw()).toString();

        int reports  = reportRepository.deleteByReceivedAtBefore(rawCutoff);
        int checks   = appCheckRepository.deleteByCheckedAtBefore(rawCutoff);
        int incidents = incidentRepository.deleteByOpenedAtBefore(historyCutoff);
        int logs     = logRepository.deleteByBefore(historyCutoff);
        int tokens   = tokenRepository.deleteExpiredBefore(tokenCutoff);

        log.info("Rétention : {} rapports, {} checks, {} incidents, {} logs, {} tokens supprimés",
                reports, checks, incidents, logs, tokens);
    }
}
