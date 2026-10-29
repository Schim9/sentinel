package lu.kaminski.sentinel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "host")
@Data
@NoArgsConstructor
public class Host {

    @Id
    private String name;

    private String model;

    @Column(name = "last_report_at")
    private String lastReportAt;  // ISO-8601

    @Column(name = "last_boot_time")
    private String lastBootTime;

    /** OK | WARN | CRIT | SILENT */
    @Column(nullable = false)
    private String state = "OK";

    public Host(String name, String model) {
        this.name  = name;
        this.model = model;
    }
}
