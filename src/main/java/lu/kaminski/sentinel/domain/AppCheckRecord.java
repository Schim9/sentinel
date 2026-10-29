package lu.kaminski.sentinel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_check")
@Data
@NoArgsConstructor
public class AppCheckRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INTEGER")
    private Long id;

    @Column(nullable = false)
    private String app;

    /** INTERNAL | EXTERNAL */
    @Column(nullable = false)
    private String kind;

    @Column(name = "checked_at", nullable = false)
    private String checkedAt;

    @Column(nullable = false, columnDefinition = "INTEGER")
    private boolean ok;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms", columnDefinition = "INTEGER")
    private Long latencyMs;

    private String error;
}
