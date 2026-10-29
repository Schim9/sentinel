package lu.kaminski.sentinel.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Utilitaire de formatage des dates accessible dans les templates Thymeleaf via {@code @dateFormatter}.
 * Convertit les timestamps ISO-8601 UTC en heure locale Europe/Paris.
 */
@Component("dateFormatter")
public class DateFormatter {

    private static final ZoneId            PARIS       = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DISPLAY     = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");
    private static final DateTimeFormatter DISPLAY_SHORT = DateTimeFormatter.ofPattern("dd/MM 'à' HH:mm");

    public String format(String iso) {
        if (iso == null) return "—";
        try {
            return Instant.parse(iso).atZone(PARIS).format(DISPLAY);
        } catch (Exception e1) {
            try {
                // Fallback pour les datetime locaux sans fuseau (ex: lastBootTime de l'agent)
                return LocalDateTime.parse(iso).atZone(PARIS).format(DISPLAY);
            } catch (Exception e2) {
                return iso;
            }
        }
    }

    public String formatShort(String iso) {
        if (iso == null) return "—";
        try {
            return Instant.parse(iso).atZone(PARIS).format(DISPLAY_SHORT);
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(iso).atZone(PARIS).format(DISPLAY_SHORT);
            } catch (Exception e2) {
                return iso;
            }
        }
    }
}
