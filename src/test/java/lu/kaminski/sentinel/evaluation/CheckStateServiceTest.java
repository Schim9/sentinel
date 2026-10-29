package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.CheckState;
import lu.kaminski.sentinel.domain.Incident;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckStateServiceTest {

    @Mock CheckStateRepository checkStateRepository;
    @Mock IncidentRepository   incidentRepository;

    CheckStateService service;

    private static final String NOW = "2026-10-18T10:00:00Z";

    @BeforeEach
    void setUp() {
        SentinelProperties props = new SentinelProperties();
        // breach-count=2, recovery-count=2 par défaut dans SentinelProperties.Evaluation
        Clock clock = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC);
        service = new CheckStateService(checkStateRepository, incidentRepository, props, clock);
    }

    // ── Anti-flapping : montée ────────────────────────────────────────────────

    @Test
    void shouldNotTransitionOnFirstBreach() {
        when(checkStateRepository.findById(any())).thenReturn(Optional.empty());

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:cpu-temp", State.WARN, "75.0°C", 75.0)), NOW);

        assertThat(transitions).isEmpty();
    }

    @Test
    void shouldTransitionToWarnAfterBreachCount() {
        when(checkStateRepository.findById("host:pi:cpu-temp"))
                .thenReturn(Optional.of(state("host:pi:cpu-temp", "OK", 1, 0)));
        when(incidentRepository.existsBySubjectKeyAndClosedAtIsNull(any())).thenReturn(false);

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:cpu-temp", State.WARN, "75.0°C", 75.0)), NOW);

        assertThat(transitions).hasSize(1);
        assertThat(transitions.get(0).newState()).isEqualTo(State.WARN);
        assertThat(transitions.get(0).oldState()).isEqualTo(State.OK);
    }

    @Test
    void shouldEscalateToCritWhenAlreadyWarn() {
        when(checkStateRepository.findById("host:pi:cpu-temp"))
                .thenReturn(Optional.of(state("host:pi:cpu-temp", "WARN", 1, 0)));
        when(incidentRepository.existsBySubjectKeyAndClosedAtIsNull(any())).thenReturn(true);

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:cpu-temp", State.CRIT, "85.0°C", 85.0)), NOW);

        assertThat(transitions).hasSize(1);
        assertThat(transitions.get(0).newState()).isEqualTo(State.CRIT);
    }

    // ── Anti-flapping : descente ──────────────────────────────────────────────

    @Test
    void shouldNotRecoverOnFirstOk() {
        when(checkStateRepository.findById("host:pi:cpu-temp"))
                .thenReturn(Optional.of(state("host:pi:cpu-temp", "WARN", 0, 0)));

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:cpu-temp", State.OK, "60.0°C", 60.0)), NOW);

        assertThat(transitions).isEmpty();
    }

    @Test
    void shouldRecoverToOkAfterRecoveryCount() {
        when(checkStateRepository.findById("host:pi:cpu-temp"))
                .thenReturn(Optional.of(state("host:pi:cpu-temp", "WARN", 0, 1)));

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:cpu-temp", State.OK, "60.0°C", 60.0)), NOW);

        assertThat(transitions).hasSize(1);
        assertThat(transitions.get(0).newState()).isEqualTo(State.OK);
    }

    // ── Transition immédiate ──────────────────────────────────────────────────

    @Test
    void shouldTransitionImmediatelyForService() {
        when(checkStateRepository.findById(any())).thenReturn(Optional.empty());
        when(incidentRepository.existsBySubjectKeyAndClosedAtIsNull(any())).thenReturn(false);

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:service:svc.service", State.CRIT, "svc.service=failed")), NOW);

        assertThat(transitions).hasSize(1);
        assertThat(transitions.get(0).newState()).isEqualTo(State.CRIT);
    }

    @Test
    void shouldTransitionImmediatelyForSilence() {
        when(checkStateRepository.findById(any())).thenReturn(Optional.empty());
        when(incidentRepository.existsBySubjectKeyAndClosedAtIsNull(any())).thenReturn(false);

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:silence", State.CRIT, "silence depuis null")), NOW);

        assertThat(transitions).hasSize(1);
    }

    @Test
    void shouldTransitionImmediatelyForDiskRo() {
        when(checkStateRepository.findById(any())).thenReturn(Optional.empty());
        when(incidentRepository.existsBySubjectKeyAndClosedAtIsNull(any())).thenReturn(false);

        List<StateTransition> transitions = service.evaluate(
                List.of(new RuleResult("host:pi:disk-ro", State.CRIT, "/ en lecture seule")), NOW);

        assertThat(transitions).hasSize(1);
    }

    // ── Gestion des incidents ─────────────────────────────────────────────────

    @Test
    void shouldOpenIncidentOnTransition() {
        when(checkStateRepository.findById("host:pi:cpu-temp"))
                .thenReturn(Optional.of(state("host:pi:cpu-temp", "OK", 1, 0)));
        when(incidentRepository.existsBySubjectKeyAndClosedAtIsNull(any())).thenReturn(false);

        service.evaluate(List.of(new RuleResult("host:pi:cpu-temp", State.WARN, "75.0°C", 75.0)), NOW);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getSubjectKey()).isEqualTo("host:pi:cpu-temp");
        assertThat(captor.getValue().getSeverity()).isEqualTo("WARN");
    }

    @Test
    void shouldNotOpenDuplicateIncident() {
        when(checkStateRepository.findById("host:pi:cpu-temp"))
                .thenReturn(Optional.of(state("host:pi:cpu-temp", "WARN", 1, 0)));
        when(incidentRepository.existsBySubjectKeyAndClosedAtIsNull(any())).thenReturn(true);

        service.evaluate(List.of(new RuleResult("host:pi:cpu-temp", State.CRIT, "85.0°C", 85.0)), NOW);

        verify(incidentRepository, never()).save(any(Incident.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CheckState state(String key, String stateStr, int breaches, int okCount) {
        CheckState cs = new CheckState(key, "2026-10-18T09:00:00Z");
        cs.setState(stateStr);
        cs.setConsecutiveBreaches(breaches);
        cs.setConsecutiveOk(okCount);
        return cs;
    }
}
