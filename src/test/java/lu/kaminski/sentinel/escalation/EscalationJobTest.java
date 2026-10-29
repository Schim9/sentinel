package lu.kaminski.sentinel.escalation;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import lu.kaminski.sentinel.ack.AccessTokenRepository;
import lu.kaminski.sentinel.ack.AccessTokenService;
import lu.kaminski.sentinel.domain.AccessToken;
import lu.kaminski.sentinel.notification.PushbulletClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class EscalationJobTest {

    // Horloge fixée : 2026-10-20T10:00:00Z
    // ackTimeout    = now - 36h = 2026-10-19T22:00:00Z
    // Tokens créés avant cette limite sont candidats à l'escalade
    private static final Instant FIXED_NOW = Instant.parse("2026-10-20T10:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test", "test"));

    @Autowired EscalationJob           escalationJob;
    @Autowired AccessTokenRepository   tokenRepository;
    @MockBean PushbulletClient         pushbulletClient;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        when(pushbulletClient.sendLink(any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void shouldSendEscalationEmail_forUnackedDigest() throws Exception {
        saveDigest("2026-10-18T20:00:00Z", null, null); // créé 37h avant FIXED_NOW
        int before = greenMail.getReceivedMessages().length;

        escalationJob.check();

        assertThat(greenMail.getReceivedMessages()).hasSize(before + 1);
        assertThat(greenMail.getReceivedMessages()[before].getSubject()).contains("escalade");
    }

    @Test
    void shouldNotEscalate_whenRecentlyEscalated() {
        // Escalade il y a 20h → intervalle de 24h pas encore écoulé
        saveDigest("2026-10-18T20:00:00Z", null, "2026-10-19T14:00:00Z");
        int before = greenMail.getReceivedMessages().length;

        escalationJob.check();

        assertThat(greenMail.getReceivedMessages()).hasSize(before);
    }

    @Test
    void shouldNotEscalate_whenAlreadyAcked() {
        saveDigest("2026-10-18T20:00:00Z", "2026-10-19T09:00:00Z", null);
        int before = greenMail.getReceivedMessages().length;

        escalationJob.check();

        assertThat(greenMail.getReceivedMessages()).hasSize(before);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveDigest(String createdAt, String ackedAt, String lastEscalationAt) {
        AccessToken token = new AccessToken();
        token.setType("DIGEST");
        token.setTokenHash(AccessTokenService.hash("digest-" + System.nanoTime()));
        token.setCreatedAt(createdAt);
        token.setExpiresAt("2026-10-27T10:00:00Z");
        token.setAckedAt(ackedAt);
        token.setLastEscalationAt(lastEscalationAt);
        tokenRepository.save(token);
    }
}
