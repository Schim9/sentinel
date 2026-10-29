package lu.kaminski.sentinel.digest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DigestJob {

    private final DigestService digestService;

    /** Digest quotidien à l'heure configurée (défaut 08:00, timezone Europe/Paris). */
    @Scheduled(cron = "#{@sentinelProperties.digest.cron}", zone = "#{@sentinelProperties.timezone}")
    public void sendDailyDigest() {
        log.info("Envoi du digest quotidien");
        digestService.sendDigest();
    }

    /** Rapport hebdomadaire le lundi à 08:00. */
    @Scheduled(cron = "#{@sentinelProperties.weeklyReport.cron}", zone = "#{@sentinelProperties.timezone}")
    public void sendWeeklyReport() {
        log.info("Envoi du rapport hebdomadaire");
        digestService.sendWeeklyReport();
    }
}
