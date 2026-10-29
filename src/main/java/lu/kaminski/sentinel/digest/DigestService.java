package lu.kaminski.sentinel.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.kaminski.sentinel.ack.AccessTokenService;
import lu.kaminski.sentinel.api.StatusService;
import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.mail.MailService;
import lu.kaminski.sentinel.notification.PushbulletClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@Slf4j
@RequiredArgsConstructor
public class DigestService {

    private final AccessTokenService tokenService;
    private final StatusService      statusService;
    private final PushbulletClient   pushbullet;
    private final MailService        mailService;
    private final SentinelProperties props;
    private final ObjectMapper       objectMapper;
    private final TemplateEngine     templateEngine;

    public void sendDigest() {
        StatusService.GlobalStatus status = statusService.currentStatus();
        String snapshotJson = serializeSnapshot(status);
        String token = tokenService.generate("DIGEST", snapshotJson);
        String url   = props.getPublicBaseUrl() + "/ack/" + token;

        boolean allOk = status.openIncidents().isEmpty()
                && status.hosts().stream().allMatch(h -> "OK".equals(h.state()));

        String title, body;
        if (allOk) {
            int piCount  = status.hosts().size();
            int appCount = status.apps().size();
            double maxTemp = status.hosts().stream()
                    .mapToDouble(h -> h.lastTemp() != null ? h.lastTemp() : 0.0).max().orElse(0.0);
            title = "✅ Monitoring : tout va bien";
            body  = String.format("%d Pi · %d apps OK · max %.0f°C", piCount, appCount, maxTemp);
        } else {
            long count = status.openIncidents().size();
            title = "⚠ Monitoring : " + count + " problème(s)";
            body  = status.openIncidents().stream()
                    .limit(5)
                    .map(i -> i.subjectKey() + " · " + i.message())
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        }

        boolean sent = pushbullet.sendLink(title, body, url, "DIGEST");
        if (!sent) {
            log.error("Pushbullet KO pour le digest, bascule e-mail");
            mailService.sendHtml("[sentinel] " + title, buildDigestHtml(title, body, url));
        }
    }

    public void sendWeeklyReport() {
        StatusService.GlobalStatus status = statusService.currentStatus();
        String subject = "[sentinel] Rapport hebdomadaire";
        String html = buildWeeklyHtml(status);
        mailService.sendHtml(subject, html);
    }

    private String serializeSnapshot(StatusService.GlobalStatus status) {
        try {
            return objectMapper.writeValueAsString(status);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildDigestHtml(String title, String body, String url) {
        Context ctx = new Context();
        ctx.setVariable("title", title);
        ctx.setVariable("body", body);
        ctx.setVariable("url", url);
        return templateEngine.process("email/digest-email", ctx);
    }

    private String buildWeeklyHtml(StatusService.GlobalStatus status) {
        Context ctx = new Context();
        ctx.setVariable("status", status);
        return templateEngine.process("email/weekly-report", ctx);
    }
}
