package lu.kaminski.sentinel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "access_token")
@Data
@NoArgsConstructor
public class AccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "INTEGER")
    private Long id;

    /** DIGEST | VIEW */
    @Column(nullable = false)
    private String type;

    /** SHA-256 hex du token brut — jamais le token en clair */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "expires_at", nullable = false)
    private String expiresAt;

    @Column(name = "snapshot_json", columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "acked_at")
    private String ackedAt;

    @Column(name = "ack_ip")
    private String ackIp;

    @Column(name = "escalation_count", nullable = false)
    private int escalationCount = 0;

    @Column(name = "last_escalation_at")
    private String lastEscalationAt;

    public boolean isAcked() {
        return ackedAt != null;
    }
}
