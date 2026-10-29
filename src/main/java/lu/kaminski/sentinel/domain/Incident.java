package lu.kaminski.sentinel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "incident")
@Data
@NoArgsConstructor
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INTEGER")
    private Long id;

    @Column(name = "subject_key", nullable = false)
    private String subjectKey;

    /** WARN | CRIT */
    @Column(nullable = false)
    private String severity;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "opened_at", nullable = false)
    private String openedAt;

    @Column(name = "closed_at")
    private String closedAt;

    public boolean isOpen() {
        return closedAt == null;
    }
}
