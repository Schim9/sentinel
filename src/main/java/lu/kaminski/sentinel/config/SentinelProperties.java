package lu.kaminski.sentinel.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.*;

@Component
@ConfigurationProperties(prefix = "sentinel")
@Validated
@Data
public class SentinelProperties {

    @NotNull  private Integer publicPort;
    @NotBlank private String publicBaseUrl;
    @NotBlank private String timezone = "Europe/Paris";

    @NotEmpty @Valid private List<HostConfig> hosts = new ArrayList<>();
    @NotEmpty @Valid private List<AppConfig>  apps  = new ArrayList<>();

    @NotNull @Valid private Thresholds    thresholds    = new Thresholds();
    @NotNull @Valid private Evaluation    evaluation    = new Evaluation();
    @NotNull @Valid private Notifications notifications = new Notifications();
    @NotNull @Valid private Digest        digest        = new Digest();
    @NotNull @Valid private WeeklyReport  weeklyReport  = new WeeklyReport();
    @NotNull @Valid private Retention     retention     = new Retention();

    // Secrets — injectés depuis secrets.yml
    @NotEmpty private Map<String, String> hostTokens = new HashMap<>();
    @NotNull @Valid private Pushbullet pushbullet = new Pushbullet();
    @NotNull @Valid private SentinelMail mail = new SentinelMail();
    private Map<String, AppAuth> appsAuth = new HashMap<>();
    @NotBlank private String refreshToken;
    private Duration refreshMinInterval = Duration.ofMinutes(10);

    // ── Lookup helpers ───────────────────────────────────────────────────────

    public Optional<HostConfig> findHost(String name) {
        return hosts.stream().filter(h -> h.getName().equals(name)).findFirst();
    }

    public Optional<AppConfig> findApp(String name) {
        return apps.stream().filter(a -> a.getName().equals(name)).findFirst();
    }

    /** Retourne les seuils effectifs pour un hôte (fusion host-level + globaux). */
    public Thresholds effectiveThresholdsFor(String hostName) {
        return findHost(hostName)
                .map(h -> h.getThresholds() != null ? thresholds.merge(h.getThresholds()) : thresholds)
                .orElse(thresholds);
    }

    public Optional<String> hostToken(String hostName) {
        return Optional.ofNullable(hostTokens.get(hostName));
    }

    public Optional<AppAuth> appAuth(String appName) {
        return Optional.ofNullable(appsAuth.get(appName));
    }

    // ── Nested classes ───────────────────────────────────────────────────────

    @Data
    public static class HostConfig {
        @NotBlank private String name;
        private String model;
        @NotBlank private String ip;
        @Valid private Thresholds thresholds; // null = use global defaults
    }

    @Data
    public static class AppConfig {
        @NotBlank private String name;
        @NotBlank private String host;
        @NotBlank private String internalUrl;
        private String externalUrl;
        private String expectBodyContains;
        private int expectedStatus = 200;
        private Map<String, String> headers = new HashMap<>();
        /** Si true, parse le JSON de la réponse pour vérifier le champ "status" et le tableau "anomalies". */
        private boolean parseStatusBody = false;
    }

    @Data
    public static class Thresholds {
        private Double cpuTempWarn      = 70.0;
        private Double cpuTempCrit      = 80.0;
        private Integer diskWarnPct     = 80;
        private Integer diskCritPct     = 90;
        private Double memAvailableWarnPct = 15.0;
        private Double memAvailableCritPct = 5.0;
        private Integer swapUsedWarnPct = 50;
        private Integer swapUsedCritPct = 85;
        private Double loadWarnPerCore  = 2.0;
        private Double loadCritPerCore  = 4.0;
        private Integer certWarnDays    = 14;
        private Integer certCritDays    = 3;

        /** Fusionne les champs non-null de `overrides` sur `this`. */
        public Thresholds merge(Thresholds overrides) {
            if (overrides == null) return this;
            Thresholds t = new Thresholds();
            t.cpuTempWarn          = overrides.cpuTempWarn          != null ? overrides.cpuTempWarn          : this.cpuTempWarn;
            t.cpuTempCrit          = overrides.cpuTempCrit          != null ? overrides.cpuTempCrit          : this.cpuTempCrit;
            t.diskWarnPct          = overrides.diskWarnPct          != null ? overrides.diskWarnPct          : this.diskWarnPct;
            t.diskCritPct          = overrides.diskCritPct          != null ? overrides.diskCritPct          : this.diskCritPct;
            t.memAvailableWarnPct  = overrides.memAvailableWarnPct  != null ? overrides.memAvailableWarnPct  : this.memAvailableWarnPct;
            t.memAvailableCritPct  = overrides.memAvailableCritPct  != null ? overrides.memAvailableCritPct  : this.memAvailableCritPct;
            t.swapUsedWarnPct      = overrides.swapUsedWarnPct      != null ? overrides.swapUsedWarnPct      : this.swapUsedWarnPct;
            t.swapUsedCritPct      = overrides.swapUsedCritPct      != null ? overrides.swapUsedCritPct      : this.swapUsedCritPct;
            t.loadWarnPerCore      = overrides.loadWarnPerCore      != null ? overrides.loadWarnPerCore      : this.loadWarnPerCore;
            t.loadCritPerCore      = overrides.loadCritPerCore      != null ? overrides.loadCritPerCore      : this.loadCritPerCore;
            t.certWarnDays         = overrides.certWarnDays         != null ? overrides.certWarnDays         : this.certWarnDays;
            t.certCritDays         = overrides.certCritDays         != null ? overrides.certCritDays         : this.certCritDays;
            return t;
        }
    }

    @Data
    public static class Evaluation {
        private Duration reportInterval    = Duration.ofMinutes(5);
        private Duration silenceThreshold  = Duration.ofMinutes(15);
        private Duration startupGrace      = Duration.ofMinutes(15);
        private int      breachCount       = 2;
        private int      recoveryCount     = 2;
        private Duration appCheckInterval  = Duration.ofMinutes(2);
        private Duration appCheckTimeout   = Duration.ofSeconds(5);
        private int      appBreachCount    = 3;
    }

    @Data
    public static class Notifications {
        private Duration    critReminderInterval = Duration.ofHours(24);
        @Valid private QuietHours quietHours = new QuietHours();
        private Duration    groupingWindow  = Duration.ofSeconds(30);

        @Data
        public static class QuietHours {
            private boolean enabled = true;
            private String start = "22:00";
            private String end   = "07:00";
        }
    }

    @Data
    public static class Digest {
        private String   cron             = "0 0 8 * * *";
        private Duration tokenValidity    = Duration.ofDays(7);
        private Duration ackTimeout       = Duration.ofHours(36);
        private Duration escalationInterval = Duration.ofHours(24);
    }

    @Data
    public static class WeeklyReport {
        private String cron = "0 0 8 * * MON";
    }

    @Data
    public static class Retention {
        private Duration raw     = Duration.ofDays(30);
        private Duration history = Duration.ofDays(365);
    }

    @Data
    public static class Pushbullet {
        @NotBlank private String accessToken;
        private String deviceIden; // optionnel
    }

    @Data
    public static class SentinelMail {
        @NotBlank private String from;
        @NotBlank private String to;
    }

    @Data
    public static class AppAuth {
        private String basicUser;
        private String basicPassword;
    }
}
