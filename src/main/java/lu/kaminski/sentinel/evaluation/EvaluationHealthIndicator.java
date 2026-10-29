package lu.kaminski.sentinel.evaluation;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Indicateur actuator custom : DOWN si aucun rapport reçu depuis 10 min.
 * Mis à jour par ReportService à chaque rapport traité.
 */
@Component("evaluation")
public class EvaluationHealthIndicator implements HealthIndicator {

    private static final Duration MAX_SILENCE = Duration.ofMinutes(10);

    private final AtomicReference<Instant> lastEvaluation = new AtomicReference<>();
    private final Clock clock;

    public EvaluationHealthIndicator(Clock clock) {
        this.clock = clock;
    }

    public void recordEvaluation() {
        lastEvaluation.set(Instant.now(clock));
    }

    @Override
    public Health health() {
        Instant last = lastEvaluation.get();
        if (last == null) {
            return Health.unknown().withDetail("reason", "Aucune évaluation depuis le démarrage").build();
        }
        Duration since = Duration.between(last, Instant.now(clock));
        if (since.compareTo(MAX_SILENCE) > 0) {
            return Health.down()
                    .withDetail("lastEvaluation", last.toString())
                    .withDetail("silenceSeconds", since.getSeconds())
                    .build();
        }
        return Health.up().withDetail("lastEvaluation", last.toString()).build();
    }
}
