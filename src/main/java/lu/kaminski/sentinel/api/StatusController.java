package lu.kaminski.sentinel.api;

import lu.kaminski.sentinel.appcheck.AppCheckJob;
import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.mail.MailService;
import lu.kaminski.sentinel.notification.PushbulletClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import lu.kaminski.sentinel.ack.AccessTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Controller
@Slf4j
public class StatusController {

    private final StatusService      statusService;
    private final PushbulletClient   pushbullet;
    private final MailService        mailService;
    private final AppCheckJob        appCheckJob;
    private final SentinelProperties props;
    private final Clock              clock;

    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);

    public StatusController(StatusService statusService, PushbulletClient pushbullet,
                            MailService mailService, AppCheckJob appCheckJob,
                            SentinelProperties props, Clock clock) {
        this.statusService = statusService;
        this.pushbullet    = pushbullet;
        this.mailService   = mailService;
        this.appCheckJob   = appCheckJob;
        this.props         = props;
        this.clock         = clock;
    }

    /** Page d'état HTML interne. */
    @GetMapping("/")
    public String statusPage(Model model) {
        model.addAttribute("status", statusService.currentStatus());
        return "status";
    }

    /** Endpoint de santé standard — format commun à toutes les apps du foyer. */
    @GetMapping("/api/status")
    @ResponseBody
    public Map<String, String> apiStatus() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("appCode", "SENTINEL");
        body.put("status", "up");
        body.put("uptime", formatUptime());
        body.put("timestamp", LocalDateTime.now(clock).toString());
        return body;
    }

    /**
     * GET /report :
     *  - port interne (8090) → rapport complet directement
     *  - port public  (8091) → formulaire de saisie du secret
     */
    @GetMapping("/report")
    public String reportHtml(HttpServletRequest request, Model model) {
        if (request.getLocalPort() == props.getPublicPort()) {
            return "refresh-form";
        }
        model.addAttribute("status", statusService.currentStatus());
        return "report";
    }

    /**
     * POST /report (port public uniquement) :
     *  - valide le secret
     *  - si OK et délai écoulé : lance les checks
     *  - affiche le rapport (ou le formulaire avec erreur)
     */
    @PostMapping("/report")
    public String handleRefresh(@RequestParam(required = false) String secret,
                                HttpServletRequest request, Model model) {
        if (request.getLocalPort() != props.getPublicPort()) {
            return "redirect:/report";
        }

        if (!isValidSecret(secret)) {
            log.warn("Tentative de refresh avec secret invalide depuis {}", request.getRemoteAddr());
            model.addAttribute("error", true);
            return "refresh-form";
        }

        Instant now = Instant.now(clock);
        Instant last = lastRefresh.get();
        Duration minInterval = props.getRefreshMinInterval();
        Duration elapsed = Duration.between(last, now);

        if (elapsed.compareTo(minInterval) >= 0 && lastRefresh.compareAndSet(last, now)) {
            Thread.ofVirtual().start(appCheckJob::run);
        } else if (elapsed.compareTo(minInterval) < 0) {
            long secondsLeft = minInterval.minus(elapsed).toSeconds();
            log.info("Refresh ignoré — rate limit actif, prochain refresh dans {}s", secondsLeft);
            model.addAttribute("rateLimitedSeconds", secondsLeft);
        }

        model.addAttribute("status", statusService.currentStatus());
        return "report";
    }

    /** Test Pushbullet. */
    @PostMapping("/api/test/push")
    public ResponseEntity<String> testPush() {
        boolean ok = pushbullet.sendLink("Test sentinel", "Test de la notification Pushbullet.", props.getPublicBaseUrl() + "/report", "TEST");
        return ok ? ResponseEntity.ok("Pushbullet OK") : ResponseEntity.status(500).body("Pushbullet KO");
    }

    /** Test e-mail. */
    @PostMapping("/api/test/mail")
    public ResponseEntity<String> testMail() {
        mailService.sendPlain("[sentinel] Test e-mail", "Ceci est un e-mail de test envoyé par sentinel.");
        return ResponseEntity.ok("E-mail envoyé");
    }

    private boolean isValidSecret(String incoming) {
        return AccessTokenService.timingSafeEquals(incoming, props.getRefreshToken());
    }

    private String formatUptime() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        Duration d = Duration.ofMillis(uptimeMs);
        long days  = d.toDays();
        long hours = d.toHoursPart();
        long mins  = d.toMinutesPart();
        if (days > 0)  return days + "j " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}
