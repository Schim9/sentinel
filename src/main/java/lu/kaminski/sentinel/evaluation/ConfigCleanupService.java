package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.config.SentinelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Nettoie au démarrage les incidents et check_states orphelins :
 * apps ou hôtes qui ne figurent plus dans la configuration.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConfigCleanupService {

    private final SentinelProperties   props;
    private final IncidentRepository   incidentRepository;
    private final CheckStateRepository checkStateRepository;
    private final Clock                clock;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void cleanupOrphanedEntries() {
        String now = Instant.now(clock).toString();

        Set<String> knownApps = props.getApps().stream()
                .map(SentinelProperties.AppConfig::getName)
                .collect(Collectors.toSet());

        Set<String> knownHosts = props.getHosts().stream()
                .map(SentinelProperties.HostConfig::getName)
                .collect(Collectors.toSet());

        incidentRepository.findByClosedAtIsNullOrderByOpenedAtDesc().stream()
                .filter(i -> isOrphaned(i.getSubjectKey(), knownApps, knownHosts))
                .forEach(i -> {
                    log.info("Fermeture incident orphelin au démarrage : {}", i.getSubjectKey());
                    i.setClosedAt(now);
                    incidentRepository.save(i);
                });

        checkStateRepository.findAll().stream()
                .filter(cs -> isOrphaned(cs.getSubjectKey(), knownApps, knownHosts))
                .forEach(cs -> {
                    log.info("Suppression check_state orphelin au démarrage : {}", cs.getSubjectKey());
                    checkStateRepository.delete(cs);
                });
    }

    private boolean isOrphaned(String subjectKey, Set<String> knownApps, Set<String> knownHosts) {
        String[] parts = subjectKey.split(":", 3);
        if (parts.length < 2) return false;
        return switch (parts[0]) {
            case "app"  -> !knownApps.contains(parts[1]);
            case "host" -> !knownHosts.contains(parts[1]);
            default     -> false;
        };
    }
}
