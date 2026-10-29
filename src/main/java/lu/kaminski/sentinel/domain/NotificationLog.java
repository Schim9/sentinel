package lu.kaminski.sentinel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_log")
@Data
@NoArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INTEGER")
    private Long id;

    /** PUSH | MAIL */
    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String kind;

    @Column(name = "sent_at", nullable = false)
    private String sentAt;

    @Column(nullable = false, columnDefinition = "INTEGER")
    private boolean success;

    private String error;
}
