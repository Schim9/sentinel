package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.CheckState;
import lu.kaminski.sentinel.domain.Incident;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Applique l'anti-flapping et produit les transitions d'état.
 * breach-count  : nombre d'évaluations consécutives en dépassement avant transition vers WARN/CRIT.
 * recovery-count: nombre d'évaluations consécutives OK avant retour à OK.
 * Exceptions : silence, lecture seule, service inactif → breach-count = 1.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CheckStateService {

    private static final int IMMEDIATE_BREACH = 1;

    private final CheckStateRepository  checkStateRepository;
    private final IncidentRepository    incidentRepository;
    private final SentinelProperties    props;
    private final Clock                 clock;

    @Transactional
    public List<StateTransition> evaluate(List<RuleResult> results, String nowIso) {
        List<StateTransition> transitions = new ArrayList<>();
        Instant now = Instant.now(clock);

        for (RuleResult result : results) {
            CheckState cs = checkStateRepository.findById(result.subjectKey())
                    .orElseGet(() -> new CheckState(result.subjectKey(), nowIso));

            State current = State.fromString(cs.getState());
            State evaluated = result.state();
            int breachThreshold   = breachCountFor(result.subjectKey());
            int recoveryThreshold = props.getEvaluation().getRecoveryCount();

            State next = current;

            if (evaluated != State.OK) {
                cs.setConsecutiveOk(0);
                cs.setConsecutiveBreaches(cs.getConsecutiveBreaches() + 1);

                if (evaluated.isWorseThan(current)
                        && cs.getConsecutiveBreaches() >= breachThreshold) {
                    next = evaluated;
                    cs.setConsecutiveBreaches(0);
                }
            } else {
                cs.setConsecutiveBreaches(0);
                cs.setConsecutiveOk(cs.getConsecutiveOk() + 1);

                if (current != State.OK && cs.getConsecutiveOk() >= recoveryThreshold) {
                    next = State.OK;
                    cs.setConsecutiveOk(0);
                }
            }

            cs.setLastValue(result.description());
            cs.setLastNumeric(result.numericValue());

            if (next != current) {
                cs.setState(next.name());
                cs.setSince(nowIso);
                log.info("Transition {} : {} → {}", result.subjectKey(), current, next);
                transitions.add(new StateTransition(result.subjectKey(), current, next, result.description(), now));

                // Gestion des incidents
                if (next != State.OK) {
                    openIncident(result.subjectKey(), next, result.description(), nowIso);
                } else {
                    closeIncidents(result.subjectKey(), nowIso);
                }
            }

            checkStateRepository.save(cs);
        }

        return transitions;
    }

    private int breachCountFor(String subjectKey) {
        // Transition immédiate pour : silence, lecture seule, service inactif
        if (subjectKey.endsWith(":silence")
                || subjectKey.endsWith(":disk-ro")
                || subjectKey.contains(":service:")) {
            return IMMEDIATE_BREACH;
        }
        return props.getEvaluation().getBreachCount();
    }

    private void openIncident(String subjectKey, State severity, String message, String now) {
        // Ne pas ouvrir si un incident ouvert existe déjà pour ce sujet
        if (incidentRepository.existsBySubjectKeyAndClosedAtIsNull(subjectKey)) return;
        Incident incident = new Incident();
        incident.setSubjectKey(subjectKey);
        incident.setSeverity(severity.name());
        incident.setMessage(message);
        incident.setOpenedAt(now);
        incidentRepository.save(incident);
    }

    private void closeIncidents(String subjectKey, String now) {
        incidentRepository.closeOpenIncidents(subjectKey, now);
    }

    /**
     * Ferme les incidents et supprime les check_states des services qui ne
     * figurent plus dans le rapport (retirés de l'agent.conf).
     */
    @Transactional
    public void closeRemovedServices(String host, List<String> activeServiceNames, String now) {
        String prefix = "host:" + host + ":service:";
        List<CheckState> toRemove =
                checkStateRepository.findBySubjectKeyStartingWith(prefix)
                        .stream()
                        .filter(cs -> !activeServiceNames.contains(cs.getSubjectKey().substring(prefix.length())))
                        .toList();

        toRemove.forEach(cs -> {
            log.info("Service absent du rapport, nettoyage : {}", cs.getSubjectKey());
            incidentRepository.closeOpenIncidents(cs.getSubjectKey(), now);
        });
        checkStateRepository.deleteAll(toRemove);
    }
}
