package lu.kaminski.sentinel.mail;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.NotificationLog;
import lu.kaminski.sentinel.notification.NotificationLogRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender           mailSender;
    private final SentinelProperties       props;
    private final NotificationLogRepository logRepository;
    private final Clock                    clock;

    public void sendPlain(String subject, String text) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(props.getMail().getFrom());
            msg.setTo(props.getMail().getTo());
            msg.setSubject(subject);
            msg.setText(text);
            mailSender.send(msg);
            log("MAIL", subject, true, null);
        } catch (Exception e) {
            log.error("Échec envoi e-mail '{}': {}", subject, e.getMessage());
            log("MAIL", subject, false, e.getMessage());
        }
    }

    public void sendHtml(String subject, String htmlContent) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(props.getMail().getFrom());
            helper.setTo(props.getMail().getTo());
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(mime);
            log("MAIL", subject, true, null);
        } catch (MessagingException e) {
            log.error("Échec envoi e-mail HTML '{}': {}", subject, e.getMessage());
            log("MAIL", subject, false, e.getMessage());
        }
    }

    private void log(String channel, String kind, boolean success, String error) {
        NotificationLog entry = new NotificationLog();
        entry.setChannel(channel);
        entry.setKind(kind);
        entry.setSentAt(Instant.now(clock).toString());
        entry.setSuccess(success);
        entry.setError(error);
        logRepository.save(entry);
    }
}
