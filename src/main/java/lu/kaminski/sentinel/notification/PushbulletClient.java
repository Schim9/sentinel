package lu.kaminski.sentinel.notification;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.NotificationLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@Slf4j
public class PushbulletClient {

    private static final String API_URL = "https://api.pushbullet.com/v2/pushes";
    private static final int[]  BACKOFFS = {5_000, 30_000}; // ms

    private final SentinelProperties  props;
    private final NotificationLogRepository logRepository;
    private final Clock               clock;
    private final HttpClient          http;

    public PushbulletClient(SentinelProperties props, NotificationLogRepository logRepository, Clock clock) {
        this.props         = props;
        this.logRepository = logRepository;
        this.clock         = clock;
        this.http          = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Envoie une notification de type `link` via Pushbullet.
     * Retente 2 fois avec backoff. Retourne true si succès.
     */
    public boolean sendLink(String title, String body, String url, String kind) {
        String json = buildJson(title, body, url);
        for (int attempt = 0; attempt <= BACKOFFS.length; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(BACKOFFS[attempt - 1]); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .timeout(Duration.ofSeconds(10))
                        .header("Access-Token", props.getPushbullet().getAccessToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log(kind, true, null);
                    return true;
                }
                log.warn("Pushbullet a répondu {} (tentative {})", response.statusCode(), attempt + 1);
                if (response.statusCode() == 401) break; // inutile de réessayer
            } catch (Exception e) {
                log.warn("Erreur Pushbullet tentative {}: {}", attempt + 1, e.getMessage());
            }
        }
        log(kind, false, "Échec après " + (BACKOFFS.length + 1) + " tentatives");
        return false;
    }

    private String buildJson(String title, String body, String url) {
        String devicePart = props.getPushbullet().getDeviceIden() != null
                ? ",\"device_iden\":\"" + escape(props.getPushbullet().getDeviceIden()) + "\""
                : "";
        return String.format(
                "{\"type\":\"link\",\"title\":\"%s\",\"body\":\"%s\",\"url\":\"%s\"%s}",
                escape(title), escape(body), escape(url), devicePart);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void log(String kind, boolean success, String error) {
        NotificationLog entry = new NotificationLog();
        entry.setChannel("PUSH");
        entry.setKind(kind);
        entry.setSentAt(Instant.now(clock).toString());
        entry.setSuccess(success);
        entry.setError(error);
        logRepository.save(entry);
    }
}
