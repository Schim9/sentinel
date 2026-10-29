package lu.kaminski.sentinel.escalation;

import lu.kaminski.sentinel.ack.AccessTokenRepository;
import lu.kaminski.sentinel.ack.AccessTokenService;
import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.AccessToken;
import lu.kaminski.sentinel.mail.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Vérifie toutes les heures si un digest n'a pas été acquitté après `ack-timeout` (36h).
 * Envoie un e-mail d'escalade, puis relance toutes les 24h.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EscalationJob {

    private final AccessTokenRepository tokenRepository;
    private final AccessTokenService    tokenService;
    private final SentinelProperties    props;
    private final MailService           mailService;
    private final Clock                 clock;

    @Scheduled(fixedDelay = 3_600_000) // toutes les heures
    @Transactional
    public void check() {
        Instant now        = Instant.now(clock);
        Instant ackTimeout = now.minus(props.getDigest().getAckTimeout());

        List<AccessToken> tokens = tokenRepository.findUnackedDigestsBefore(ackTimeout.toString());
        for (AccessToken token : tokens) {
            boolean needsEscalation = token.getLastEscalationAt() == null
                    || Instant.parse(token.getLastEscalationAt())
                    .isBefore(now.minus(props.getDigest().getEscalationInterval()));

            if (needsEscalation) {
                sendEscalation(token, now);
                token.setEscalationCount(token.getEscalationCount() + 1);
                token.setLastEscalationAt(now.toString());
                tokenRepository.save(token);
            }
        }
    }

    private void sendEscalation(AccessToken token, Instant now) {
        String url     = props.getPublicBaseUrl() + "/ack/" + findTokenValue(token);
        String subject = "[sentinel] Aucun accusé de lecture — escalade";
        String body    = String.format(
                "Aucun accusé de lecture depuis le digest du %s.%n%n" +
                "Causes possibles : application Pushbullet inactive, notification manquée.%n%n" +
                "Voir le rapport : %s%n%n" +
                "Escalade n° %d",
                token.getCreatedAt(), url, token.getEscalationCount() + 1);

        log.warn("Escalade digest {} (escalade n° {})", token.getId(), token.getEscalationCount() + 1);
        mailService.sendPlain(subject, body);
    }

    private String findTokenValue(AccessToken token) {
        return tokenService.generate("DIGEST", token.getSnapshotJson());
    }
}
