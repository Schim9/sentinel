package lu.kaminski.sentinel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "report")
@Data
@NoArgsConstructor
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INTEGER")
    private Long id;

    @Column(nullable = false)
    private String host;

    @Column(name = "received_at", nullable = false)
    private String receivedAt;

    @Column(name = "collected_at", nullable = false)
    private String collectedAt;

    @Column(nullable = false)
    private String mode;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "cpu_temp_c")
    private Double cpuTempC;

    @Column(name = "mem_available_pct")
    private Double memAvailablePct;

    @Column(name = "swap_used_pct")
    private Double swapUsedPct;

    @Column(name = "max_disk_pct")
    private Double maxDiskPct;

    private Double load1;

    @Column(name = "throttled_raw")
    private String throttledRaw;
}
