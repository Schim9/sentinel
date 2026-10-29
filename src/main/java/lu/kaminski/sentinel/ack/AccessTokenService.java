package lu.kaminski.sentinel.ack;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.AccessToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccessTokenService {

    private final AccessTokenRepository tokenRepository;
    private final SentinelProperties    props;
    private final Clock                 clock;
    private final SecureRandom          random = new SecureRandom();

    /**
     * Génère un token aléatoire, le persiste (hash uniquement) et retourne le token en clair.
     * @param type         DIGEST | VIEW
     * @param snapshotJson snapshot JSON de l'état au moment de l'envoi (nullable)
     */
    @Transactional
    public String generate(String type, String snapshotJson) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now     = Instant.now(clock);
        Instant expires = now.plus(props.getDigest().getTokenValidity());

        AccessToken entity = new AccessToken();
        entity.setType(type);
        entity.setTokenHash(hash(token));
        entity.setCreatedAt(now.toString());
        entity.setExpiresAt(expires.toString());
        entity.setSnapshotJson(snapshotJson);
        tokenRepository.save(entity);

        return token;
    }

    /** Recherche un token valide (non expiré) par sa valeur en clair. */
    public Optional<AccessToken> find(String rawToken) {
        String h = hash(rawToken);
        return tokenRepository.findByTokenHash(h)
                .filter(t -> Instant.parse(t.getExpiresAt()).isAfter(Instant.now(clock)));
    }

    /** Enregistre l'accusé de lecture (idempotent). */
    @Transactional
    public void acknowledge(AccessToken token, String ip) {
        if (token.isAcked()) return;
        token.setAckedAt(Instant.now(clock).toString());
        token.setAckIp(ip);
        tokenRepository.save(token);

        // Acquitte aussi les digests plus anciens encore ouverts
        tokenRepository.acknowledgeOlderDigests(token.getCreatedAt(), Instant.now(clock).toString());
    }

    public static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Comparaison en temps constant de deux valeurs en clair via leur hash SHA-256. */
    public static boolean timingSafeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                hash(a).getBytes(StandardCharsets.UTF_8),
                hash(b).getBytes(StandardCharsets.UTF_8));
    }
}
