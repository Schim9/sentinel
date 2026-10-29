package lu.kaminski.sentinel.evaluation;

import java.time.Instant;

/**
 * Transition d'état pour un sujet (après application de l'anti-flapping).
 */
public record StateTransition(
        String subjectKey,
        State  oldState,
        State  newState,
        String message,
        Instant when
) {
    public boolean isResolved() {
        return newState == State.OK && oldState != State.OK;
    }

    public boolean isSilence() {
        return subjectKey.endsWith(":silence");
    }
}
