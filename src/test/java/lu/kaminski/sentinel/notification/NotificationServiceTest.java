package lu.kaminski.sentinel.notification;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.evaluation.State;
import lu.kaminski.sentinel.evaluation.StateTransition;
import lu.kaminski.sentinel.mail.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock PushbulletClient pushbullet;
    @Mock MailService      mailService;

    // 23h00 Europe/Paris (UTC+2 en octobre) → en heures calmes (22h-07h)
    private static final Instant QUIET_TIME = Instant.parse("2026-10-18T21:00:00Z");
    // 08h00 Europe/Paris → hors heures calmes
    private static final Instant DAYTIME    = Instant.parse("2026-10-18T06:00:00Z");
    private static final ZoneId  PARIS      = ZoneId.of("Europe/Paris");

    // Clock mutable : permet de simuler le passage de la nuit au matin dans un même test
    private AtomicReference<Instant> clockRef;
    private NotificationService      svc;

    @BeforeEach
    void setUp() {
        clockRef = new AtomicReference<>(DAYTIME);
        svc = buildService(clockRef);
    }

    private NotificationService buildService(AtomicReference<Instant> ref) {
        SentinelProperties props = new SentinelProperties();
        props.setPublicBaseUrl("http://localhost:8091");
        props.setTimezone("Europe/Paris");

        SentinelProperties.Notifications notifs = new SentinelProperties.Notifications();
        notifs.setGroupingWindow(Duration.ofMillis(20));
        SentinelProperties.Notifications.QuietHours qh = new SentinelProperties.Notifications.QuietHours();
        qh.setEnabled(true);
        qh.setStart("22:00");
        qh.setEnd("07:00");
        notifs.setQuietHours(qh);
        props.setNotifications(notifs);

        Clock mutableClock = new Clock() {
            @Override public ZoneId getZone() { return PARIS; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(ref.get(), zone); }
            @Override public Instant instant() { return ref.get(); }
        };
        return new NotificationService(props, pushbullet, mailService, mutableClock);
    }

    // ── Hors heures calmes ────────────────────────────────────────────────────

    @Test
    void shouldSendImmediatelyOutsideQuietHours() throws InterruptedException {
        lenient().when(pushbullet.sendLink(any(), any(), any(), any())).thenReturn(true);
        // clockRef = DAYTIME par défaut

        svc.enqueue(List.of(warn("host:pi:cpu-temp")));
        Thread.sleep(100);

        verify(pushbullet).sendLink(any(), any(), any(), eq("ALERT"));
    }

    // ── CRIT pendant les heures calmes ────────────────────────────────────────

    @Test
    void shouldSendCritImmediatelyDuringQuietHours() throws InterruptedException {
        lenient().when(pushbullet.sendLink(any(), any(), any(), any())).thenReturn(true);
        clockRef.set(QUIET_TIME);

        svc.enqueue(List.of(crit("host:pi:disk:/")));
        Thread.sleep(100);

        verify(pushbullet).sendLink(any(), any(), any(), eq("ALERT"));
    }

    // ── WARN différé pendant les heures calmes ────────────────────────────────

    @Test
    void shouldDeferWarnDuringQuietHours() throws InterruptedException {
        clockRef.set(QUIET_TIME);

        svc.enqueue(List.of(warn("host:pi:mem")));
        Thread.sleep(100);

        verify(pushbullet, never()).sendLink(any(), any(), any(), any());
    }

    @Test
    void shouldSendRecapWithActiveAlert() throws InterruptedException {
        lenient().when(pushbullet.sendLink(any(), any(), any(), any())).thenReturn(true);

        clockRef.set(QUIET_TIME); // 23h → heures calmes, WARN différé
        svc.enqueue(List.of(warn("host:pi:mem")));
        Thread.sleep(100);

        clockRef.set(DAYTIME); // 08h → fin des heures calmes
        svc.flushDeferred();

        verify(pushbullet).sendLink(contains("récapitulatif"), any(), any(), eq("QUIET_SUMMARY"));
    }

    @Test
    void shouldSendRecapWithResolvedAlert() throws InterruptedException {
        lenient().when(pushbullet.sendLink(any(), any(), any(), any())).thenReturn(true);

        clockRef.set(QUIET_TIME);
        svc.enqueue(List.of(warn("host:pi:mem")));   // WARN différé
        Thread.sleep(100);
        svc.enqueue(List.of(resolved("host:pi:mem"))); // résolution différée
        Thread.sleep(100);

        clockRef.set(DAYTIME);
        svc.flushDeferred();

        verify(pushbullet).sendLink(contains("✅"), any(), any(), eq("QUIET_SUMMARY"));
    }

    @Test
    void shouldNotSendRecapWhenNothingDeferred() {
        // Pas d'enqueue → rien dans deferred
        svc.flushDeferred();

        verify(pushbullet, never()).sendLink(any(), any(), any(), any());
    }

    // ── Bascule e-mail ────────────────────────────────────────────────────────

    @Test
    void shouldFallbackToEmailWhenPushbulletFails() throws InterruptedException {
        when(pushbullet.sendLink(any(), any(), any(), any())).thenReturn(false);
        // clockRef = DAYTIME par défaut

        svc.enqueue(List.of(crit("host:pi:disk:/")));
        Thread.sleep(100);

        verify(mailService).sendPlain(any(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private StateTransition warn(String key) {
        return new StateTransition(key, State.OK, State.WARN, "seuil dépassé",
                Instant.parse("2026-10-18T21:05:00Z"));
    }

    private StateTransition crit(String key) {
        return new StateTransition(key, State.OK, State.CRIT, "critique",
                Instant.parse("2026-10-18T21:05:00Z"));
    }

    private StateTransition resolved(String key) {
        return new StateTransition(key, State.WARN, State.OK, "résolu",
                Instant.parse("2026-10-18T23:30:00Z"));
    }
}
