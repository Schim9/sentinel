package lu.kaminski.sentinel.ack;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.AccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessTokenServiceTest {

    @Mock AccessTokenRepository tokenRepository;

    AccessTokenService service;
    Clock              clock;

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SentinelProperties props = new SentinelProperties();
        // token-validity = 7 jours par défaut dans SentinelProperties.Digest
        service = new AccessTokenService(tokenRepository, props, clock);
    }

    // ── generate ──────────────────────────────────────────────────────────────

    @Test
    void shouldGenerateToken_andStoreHash() {
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String rawToken = service.generate("DIGEST", null);

        assertThat(rawToken).isNotBlank();

        ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokenRepository).save(captor.capture());
        AccessToken saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("DIGEST");
        assertThat(saved.getTokenHash()).isEqualTo(AccessTokenService.hash(rawToken));
        assertThat(saved.getCreatedAt()).isEqualTo(NOW.toString());
        assertThat(saved.getSnapshotJson()).isNull();
    }

    @Test
    void shouldStoreSnapshotJson_whenProvided() {
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generate("VIEW", "{\"status\":\"ok\"}");

        ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getSnapshotJson()).isEqualTo("{\"status\":\"ok\"}");
    }

    // ── find ─────────────────────────────────────────────────────────────────

    @Test
    void shouldFindValidToken() {
        String rawToken = "test-raw-token";
        AccessToken entity = tokenWithExpiry(rawToken, NOW.plusSeconds(3600));
        when(tokenRepository.findByTokenHash(AccessTokenService.hash(rawToken)))
                .thenReturn(Optional.of(entity));

        Optional<AccessToken> result = service.find(rawToken);

        assertThat(result).isPresent();
        assertThat(result.get().getType()).isEqualTo("DIGEST");
    }

    @Test
    void shouldRejectExpiredToken() {
        String rawToken = "expired-token";
        AccessToken entity = tokenWithExpiry(rawToken, NOW.minusSeconds(1));
        when(tokenRepository.findByTokenHash(AccessTokenService.hash(rawToken)))
                .thenReturn(Optional.of(entity));

        Optional<AccessToken> result = service.find(rawToken);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmpty_whenHashNotFound() {
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThat(service.find("unknown")).isEmpty();
    }

    // ── acknowledge ───────────────────────────────────────────────────────────

    @Test
    void shouldAcknowledgeToken() {
        AccessToken token = new AccessToken();
        token.setCreatedAt(NOW.minusSeconds(3600).toString());

        service.acknowledge(token, "192.168.0.1");

        assertThat(token.getAckedAt()).isEqualTo(NOW.toString());
        assertThat(token.getAckIp()).isEqualTo("192.168.0.1");
        verify(tokenRepository).save(token);
        verify(tokenRepository).acknowledgeOlderDigests(any(), any());
    }

    @Test
    void shouldBeIdempotent_whenAlreadyAcked() {
        AccessToken token = new AccessToken();
        token.setAckedAt(NOW.minusSeconds(60).toString());

        service.acknowledge(token, "192.168.0.1");

        verify(tokenRepository, never()).save(any());
    }

    // ── timingSafeEquals ─────────────────────────────────────────────────────

    @Test
    void shouldReturnTrue_forEqualStrings() {
        assertThat(AccessTokenService.timingSafeEquals("secret", "secret")).isTrue();
    }

    @Test
    void shouldReturnFalse_forDifferentStrings() {
        assertThat(AccessTokenService.timingSafeEquals("secret", "other")).isFalse();
    }

    @Test
    void shouldReturnFalse_forNullArguments() {
        assertThat(AccessTokenService.timingSafeEquals(null, "secret")).isFalse();
        assertThat(AccessTokenService.timingSafeEquals("secret", null)).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AccessToken tokenWithExpiry(String rawToken, Instant expiresAt) {
        AccessToken entity = new AccessToken();
        entity.setType("DIGEST");
        entity.setTokenHash(AccessTokenService.hash(rawToken));
        entity.setCreatedAt(NOW.minusSeconds(3600).toString());
        entity.setExpiresAt(expiresAt.toString());
        return entity;
    }
}
