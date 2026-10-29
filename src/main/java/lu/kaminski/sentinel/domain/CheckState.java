package lu.kaminski.sentinel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "check_state")
@Data
@NoArgsConstructor
public class CheckState {

    /** Ex: "host:pi-backend-1:disk:/", "app:domoticz:external" */
    @Id
    @Column(name = "subject_key")
    private String subjectKey;

    /** OK | WARN | CRIT */
    @Column(nullable = false)
    private String state = "OK";

    @Column(name = "consecutive_breaches", nullable = false)
    private int consecutiveBreaches = 0;

    @Column(name = "consecutive_ok", nullable = false)
    private int consecutiveOk = 0;

    /** ISO-8601 — date du dernier changement d'état */
    @Column(nullable = false)
    private String since;

    @Column(name = "last_value")
    private String lastValue;

    /** Valeur numérique brute (null pour les règles non-numériques). */
    @Column(name = "last_numeric")
    private Double lastNumeric;

    @Column(name = "last_notified_at")
    private String lastNotifiedAt;

    public CheckState(String subjectKey, String since) {
        this.subjectKey = subjectKey;
        this.since      = since;
    }
}
