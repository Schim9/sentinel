package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.Host;
import lu.kaminski.sentinel.notification.NotificationService;
import lu.kaminski.sentinel.report.HostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Vérifie toutes les minutes si un hôte n'a pas envoyé de rapport
 * depuis plus de `silence-threshold` (15 min par défaut).
 * Délai de grâce de `startup-grace` (15 min) après le démarrage de sentinel.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SilenceMonitorJob {

    private final SentinelProperties props;
    private final HostRepository     hostRepository;
    private final CheckStateService  checkStateService;
    private final NotificationService notificationService;
    private final Clock              clock;

    private Instant startedAt;

    @PostConstruct
    void init() {
        this.startedAt = Instant.now(clock);
    }

    @Scheduled(fixedDelay = 60_000)
    public void check() {
        Instant now = Instant.now(clock);

        // Délai de grâce au démarrage
        if (now.isBefore(startedAt.plus(props.getEvaluation().getStartupGrace()))) {
            return;
        }

        String nowIso = now.toString();
        Instant silenceThreshold = now.minus(props.getEvaluation().getSilenceThreshold());
        List<RuleResult> results = new ArrayList<>();

        for (Host host : hostRepository.findAll()) {
            String key = "host:" + host.getName() + ":silence";
            boolean silent = host.getLastReportAt() == null
                    || Instant.parse(host.getLastReportAt()).isBefore(silenceThreshold);

            results.add(new RuleResult(key,
                    silent ? State.CRIT : State.OK,
                    silent ? "silence depuis " + host.getLastReportAt() : "OK"));

            // Mise à jour de l'état de l'hôte
            String newState = silent ? "SILENT" : "OK";
            if (!newState.equals(host.getState())) {
                hostRepository.updateState(host.getName(), newState);
            }
        }

        List<StateTransition> transitions = checkStateService.evaluate(results, nowIso);
        if (!transitions.isEmpty()) {
            notificationService.enqueue(transitions);
        }
    }
}
