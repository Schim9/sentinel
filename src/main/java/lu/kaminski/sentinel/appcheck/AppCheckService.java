package lu.kaminski.sentinel.appcheck;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.config.SentinelProperties.AppConfig;
import lu.kaminski.sentinel.domain.AppCheckRecord;
import lu.kaminski.sentinel.evaluation.RuleResult;
import lu.kaminski.sentinel.evaluation.State;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

@Service
@Slf4j
@RequiredArgsConstructor
public class AppCheckService {

    private final SentinelProperties props;
    private final AppCheckRepository appCheckRepository;
    private final Clock clock;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper;

    private record WriteRecord(String app, String kind, CheckResult result, String checkedAt) {}
    private record AppOutcome(List<RuleResult> rules, List<WriteRecord> writes) {}

    /**
     * Effectue tous les checks en deux phases :
     *  1. Checks HTTP en parallèle via virtual threads (aucun accès DB)
     *  2. Persistance séquentielle après tous les checks — évite la contention sur la connexion SQLite unique
     */
    public List<RuleResult> checkAll() {
        List<AppOutcome> outcomes = new ArrayList<>();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<AppOutcome>> futures = props.getApps().stream()
                    .map(app -> executor.submit(() -> checkApp(app)))
                    .toList();

            for (var future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (Exception e) {
                    log.warn("Erreur lors du check d'une app", e);
                }
            }
        }

        // Écritures séquentielles : un seul thread accède à la DB à la fois
        outcomes.forEach(o -> o.writes().forEach(w -> persist(w.app(), w.kind(), w.result(), w.checkedAt())));

        return outcomes.stream().flatMap(o -> o.rules().stream()).toList();
    }

    private AppOutcome checkApp(AppConfig app) {
        String now = Instant.now(clock).toString();
        List<RuleResult> results = new ArrayList<>();
        List<WriteRecord> writes = new ArrayList<>();

        CheckResult internal = doCheck(app, app.getInternalUrl(), props.appAuth(app.getName()));
        writes.add(new WriteRecord(app.getName(), "INTERNAL", internal, now));
        results.add(toRuleResult(app.getName(), "internal", internal));

        if (app.isParseStatusBody() && internal.ok() && internal.body() != null) {
            results.addAll(parseStatusResponse(app, internal.body()));
        }

        if (app.getExternalUrl() != null && !app.getExternalUrl().isBlank()) {
            CheckResult external = doCheck(app, app.getExternalUrl(), props.appAuth(app.getName()));
            writes.add(new WriteRecord(app.getName(), "EXTERNAL", external, now));
            results.add(toRuleResult(app.getName(), "external", external));

            // Diagnostic croisé
            diagnose(app.getName(), internal, external).ifPresent(results::add);

            // Certificat (max une fois par jour via check externe)
            checkCert(app, external.certExpiry()).ifPresent(results::add);
        }

        return new AppOutcome(results, writes);
    }

    private CheckResult doCheck(AppConfig app, String url, Optional<SentinelProperties.AppAuth> auth) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(props.getEvaluation().getAppCheckTimeout())
                    .GET();

            auth.ifPresent(a -> {
                String credentials = a.getBasicUser() + ":" + a.getBasicPassword();
                builder.header("Authorization", "Basic " +
                        Base64.getEncoder().encodeToString(credentials.getBytes()));
            });

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            long latency = System.currentTimeMillis() - start;
            boolean ok = response.statusCode() == app.getExpectedStatus();
            if (ok && app.getExpectBodyContains() != null) {
                ok = response.body().contains(app.getExpectBodyContains());
            }

            // Extraction de la date d'expiration du cert pour les URL HTTPS
            LocalDate certExpiry = extractCertExpiry(response);

            return new CheckResult(ok, response.statusCode(), latency, null, certExpiry, response.body());

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new CheckResult(false, null, latency, e.getMessage(), null, null);
        }
    }

    private LocalDate extractCertExpiry(HttpResponse<?> response) {
        try {
            Optional<SSLSession> sslSession = response.sslSession();
            if (sslSession.isEmpty()) return null;
            java.security.cert.Certificate[] certs;
            try {
                certs = sslSession.get().getPeerCertificates();
            } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
                return null;
            }
            if (certs.length == 0) return null;
            return ((X509Certificate) certs[0]).getNotAfter().toInstant()
                    .atZone(clock.getZone()).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private RuleResult toRuleResult(String appName, String kind, CheckResult result) {
        String key = "app:" + appName + ":" + kind;
        String desc = result.ok()  ? "OK " + result.latencyMs() + "ms"
                    : result.error() != null ? result.error()
                    : "HTTP " + result.httpStatus();
        return new RuleResult(key, result.ok() ? State.OK : State.CRIT, desc);
    }

    private List<RuleResult> parseStatusResponse(AppConfig app, String body) {
        List<RuleResult> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);

            // Champ "status"
            String statusField = root.path("status").asText("unknown");
            String statusKey = "app:" + app.getName() + ":app-status";
            if ("up".equalsIgnoreCase(statusField)) {
                results.add(new RuleResult(statusKey, State.OK, "status=up"));
            } else {
                results.add(new RuleResult(statusKey, State.CRIT,
                        app.getName() + " status=" + statusField));
            }

            // Champ "anomalies" (si présent)
            JsonNode anomalies = root.path("anomalies");
            if (!anomalies.isMissingNode() && anomalies.isArray()) {
                String anomaliesKey = "app:" + app.getName() + ":anomalies";
                if (anomalies.size() > 0) {
                    StringJoiner sj = new StringJoiner(", ");
                    for (JsonNode a : anomalies) {
                        sj.add(a.asText());
                    }
                    results.add(new RuleResult(anomaliesKey, State.WARN, sj.toString()));
                } else {
                    results.add(new RuleResult(anomaliesKey, State.OK, "no anomalies"));
                }
            }
        } catch (Exception e) {
            log.warn("Impossible de parser le corps /status pour {}: {}", app.getName(), e.getMessage());
        }
        return results;
    }

    private Optional<RuleResult> diagnose(String appName, CheckResult internal, CheckResult external) {
        String key = "app:" + appName + ":proxy";
        if (internal.ok() && !external.ok()) {
            return Optional.of(new RuleResult(key, State.WARN,
                    appName + " tourne mais est injoignable via le proxy (NAS, certificat, DNS ou routeur)"));
        }
        if (!internal.ok() && external.ok()) {
            return Optional.of(new RuleResult(key, State.WARN,
                    "incohérence : check interne KO, externe OK (vérifier internal-url)"));
        }
        return Optional.of(new RuleResult(key, State.OK, "OK"));
    }

    private Optional<RuleResult> checkCert(AppConfig app, LocalDate certExpiry) {
        if (certExpiry == null) return Optional.empty();
        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(clock), certExpiry);
        String key = "app:" + app.getName() + ":cert";
        int warnDays = props.getThresholds().getCertWarnDays();
        int critDays = props.getThresholds().getCertCritDays();
        if (daysLeft <= critDays) {
            return Optional.of(new RuleResult(key, State.CRIT,
                    "certificat expire dans " + daysLeft + " jour(s)"));
        } else if (daysLeft <= warnDays) {
            return Optional.of(new RuleResult(key, State.WARN,
                    "certificat expire dans " + daysLeft + " jour(s)"));
        }
        return Optional.of(new RuleResult(key, State.OK, "cert OK (" + daysLeft + "j)"));
    }

    private void persist(String app, String kind, CheckResult r, String now) {
        AppCheckRecord record = new AppCheckRecord();
        record.setApp(app);
        record.setKind(kind);
        record.setCheckedAt(now);
        record.setOk(r.ok());
        record.setHttpStatus(r.httpStatus());
        record.setLatencyMs(r.latencyMs());
        record.setError(r.error());
        appCheckRepository.save(record);
    }

    record CheckResult(boolean ok, Integer httpStatus, long latencyMs, String error, LocalDate certExpiry, String body) {}
}
