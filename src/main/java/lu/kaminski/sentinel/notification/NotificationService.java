package lu.kaminski.sentinel.notification;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.evaluation.State;
import lu.kaminski.sentinel.evaluation.StateTransition;
import lu.kaminski.sentinel.mail.MailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Regroupe les transitions dans une fenêtre de 30 s et les envoie via Pushbullet.
 * Heures calmes : WARN et "résolu" sont différés ; CRIT envoyé immédiatement.
 * En cas d'échec Pushbullet : bascule vers e-mail.
 */
@Service
@Slf4j
public class NotificationService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final SentinelProperties props;
    private final PushbulletClient   pushbullet;
    private final MailService        mailService;
    private final Clock              clock;
    private final ZoneId             zone;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final ConcurrentLinkedQueue<StateTransition>        pending  = new ConcurrentLinkedQueue<>();
    // Limitation connue : deferred est en mémoire uniquement. Un redémarrage pendant la plage calme
    // fait perdre les transitions WARN différées. Impact limité : le prochain rapport Pi (toutes les
    // 5 min) réinjecte les états — l'alerte sera rattrapée au cycle suivant. Pas de persistance DB
    // intentionnellement : complexité disproportionnée pour une supervision domestique.
    private final ConcurrentHashMap<String, DeferredTransition> deferred = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> sendFuture;
    private volatile ScheduledFuture<?> deferredFuture;

    private record DeferredTransition(
            StateTransition onset,      // WARN/CRIT survenu pendant la plage calme (peut être null)
            StateTransition resolution  // OK survenu pendant la plage calme (peut être null)
    ) {
        boolean isResolvedDuring() { return resolution != null; }

        DeferredTransition withResolution(StateTransition res) {
            return new DeferredTransition(onset, res);
        }
    }

    public NotificationService(SentinelProperties props, PushbulletClient pushbullet,
                                MailService mailService, Clock clock) {
        this.props       = props;
        this.pushbullet  = pushbullet;
        this.mailService = mailService;
        this.clock       = clock;
        this.zone        = ZoneId.of(props.getTimezone());
    }

    /** Ajoute des transitions à la file et (re)démarre la fenêtre de regroupement. */
    public void enqueue(List<StateTransition> transitions) {
        pending.addAll(transitions);
        scheduleFlush();
    }

    public void notifyReboot(String host, String bootTime) {
        String title = "ℹ Monitoring : " + host + " a redémarré";
        String body  = "Redémarrage détecté à " + bootTime;
        sendOrFallback(title, body, buildAckUrl(null), "REBOOT");
    }

    public void notifyStartup() {
        sendOrFallback("ℹ sentinel démarré", "sentinel est opérationnel.", buildAckUrl(null), "STARTUP");
    }

    private synchronized void scheduleFlush() {
        if (sendFuture != null && !sendFuture.isDone()) return;
        long windowMs = props.getNotifications().getGroupingWindow().toMillis();
        sendFuture = scheduler.schedule(this::flush, windowMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void scheduleFlushDeferred() {
        if (deferredFuture != null && !deferredFuture.isDone()) return;
        long delayMs = Duration.between(Instant.now(clock), nextQuietEnd()).toMillis();
        deferredFuture = scheduler.schedule(this::flushDeferred, delayMs, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        List<StateTransition> batch = new ArrayList<>();
        StateTransition t;
        while ((t = pending.poll()) != null) batch.add(t);
        if (batch.isEmpty()) return;

        boolean quietHour = isQuietHour();
        List<StateTransition> toSend = new ArrayList<>();

        for (StateTransition tr : batch) {
            if (quietHour && tr.newState() != State.CRIT) {
                deferTransition(tr);
            } else {
                toSend.add(tr);
            }
        }

        if (!deferred.isEmpty()) {
            scheduleFlushDeferred();
        }

        if (toSend.isEmpty()) return;

        String title = buildTitle(toSend);
        String body  = buildBody(toSend);
        sendOrFallback(title, body, buildAckUrl(null), "ALERT");
    }

    private void deferTransition(StateTransition t) {
        deferred.compute(t.subjectKey(), (key, existing) -> {
            if (existing == null) {
                // Résolution d'un état antérieur à la plage calme : onset null, resolution renseignée
                return t.isResolved()
                        ? new DeferredTransition(null, t)
                        : new DeferredTransition(t, null);
            }
            if (t.isResolved()) {
                return existing.withResolution(t);
            }
            // Nouvelle dégradation sur le même sujet : remplace l'entrée
            return new DeferredTransition(t, null);
        });
    }

    void flushDeferred() {
        // Sécurité : si on est encore en heures calmes (décalage d'horloge), replanifier
        if (isQuietHour()) {
            scheduleFlushDeferred();
            return;
        }
        if (deferred.isEmpty()) return;

        Map<String, DeferredTransition> snapshot = new HashMap<>(deferred);
        deferred.clear();

        List<String> activeLines   = new ArrayList<>();
        List<String> resolvedLines = new ArrayList<>();

        for (DeferredTransition d : snapshot.values()) {
            if (d.isResolvedDuring()) {
                String subject = labelFor(d.resolution().subjectKey());
                if (d.onset() != null) {
                    resolvedLines.add("✅ " + subject + " → WARN à " + formatTime(d.onset().when()) + ", résolu à " + formatTime(d.resolution().when()));
                } else {
                    resolvedLines.add("✅ " + subject + " → résolu à " + formatTime(d.resolution().when()));
                }
            } else if (d.onset() != null) {
                activeLines.add("⚠ " + labelFor(d.onset().subjectKey()) + " → " + d.onset().newState() + " à " + formatTime(d.onset().when()) + " (encore actif)");
            }
        }

        if (activeLines.isEmpty() && resolvedLines.isEmpty()) return;

        String title = activeLines.isEmpty()
                ? "✅ Monitoring — récapitulatif nuit"
                : "⚠ Monitoring — récapitulatif nuit";
        StringBuilder body = new StringBuilder();
        activeLines.forEach(l  -> body.append(l).append("\n"));
        resolvedLines.forEach(l -> body.append(l).append("\n"));

        log.info("Récapitulatif heures calmes : {} actif(s), {} résolu(s)", activeLines.size(), resolvedLines.size());
        sendOrFallback(title, body.toString().trim(), buildAckUrl(null), "QUIET_SUMMARY");
    }

    private String buildTitle(List<StateTransition> transitions) {
        long critCount = transitions.stream().filter(t -> t.newState() == State.CRIT).count();
        long resolvedCount = transitions.stream().filter(StateTransition::isResolved).count();

        if (critCount > 0 && transitions.size() == 1) {
            return "🔴 Monitoring : " + labelFor(transitions.get(0).subjectKey()) + " critique";
        }
        if (resolvedCount == transitions.size()) {
            return "✅ Monitoring : résolu";
        }
        return "⚠ Monitoring : " + transitions.size() + " problème(s)";
    }

    private String buildBody(List<StateTransition> transitions) {
        StringBuilder sb = new StringBuilder();
        for (StateTransition t : transitions) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(labelFor(t.subjectKey())).append(" · ").append(t.message());
        }
        return sb.toString();
    }

    private void sendOrFallback(String title, String body, String url, String kind) {
        if (!pushbullet.sendLink(title, body, url, kind)) {
            log.error("Pushbullet en échec, bascule e-mail");
            mailService.sendPlain("[sentinel] " + title, body + "\n\n" + url);
        }
    }

    private boolean isQuietHour() {
        if (!props.getNotifications().getQuietHours().isEnabled()) return false;
        LocalTime now  = LocalTime.now(clock.withZone(zone));
        LocalTime start = LocalTime.parse(props.getNotifications().getQuietHours().getStart());
        LocalTime end   = LocalTime.parse(props.getNotifications().getQuietHours().getEnd());
        if (start.isBefore(end)) {
            return now.isAfter(start) && now.isBefore(end);
        }
        // Plage couvre minuit (ex. 22:00–07:00)
        return now.isAfter(start) || now.isBefore(end);
    }

    private Instant nextQuietEnd() {
        LocalTime endTime = LocalTime.parse(props.getNotifications().getQuietHours().getEnd());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        ZonedDateTime candidate = now.toLocalDate().atTime(endTime).atZone(zone);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private String formatTime(Instant instant) {
        return instant.atZone(zone).format(TIME_FMT);
    }

    private String labelFor(String subjectKey) {
        String[] parts = subjectKey.split(":");
        if (parts.length >= 3) return parts[1] + " · " + parts[2];
        return subjectKey;
    }

    private String buildAckUrl(String token) {
        if (token == null) return props.getPublicBaseUrl() + "/report";
        return props.getPublicBaseUrl() + "/ack/" + token;
    }
}
