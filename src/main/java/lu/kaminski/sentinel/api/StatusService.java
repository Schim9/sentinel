package lu.kaminski.sentinel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.kaminski.sentinel.appcheck.AppCheckRepository;
import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.Host;
import lu.kaminski.sentinel.domain.CheckState;
import lu.kaminski.sentinel.evaluation.CheckStateRepository;
import lu.kaminski.sentinel.evaluation.IncidentRepository;
import lu.kaminski.sentinel.report.HostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class StatusService {

    private final HostRepository       hostRepository;
    private final CheckStateRepository checkStateRepository;
    private final IncidentRepository   incidentRepository;
    private final AppCheckRepository   appCheckRepository;
    private final SentinelProperties   props;
    private final ObjectMapper         objectMapper;
    private final Clock                clock;

    public GlobalStatus currentStatus() {
        List<HostSummary> hosts = hostRepository.findAll().stream()
                .map(this::toHostSummary)
                .toList();

        List<AppSummary> apps = props.getApps().stream()
                .map(this::toAppSummary)
                .toList();

        List<IncidentSummary> incidents = incidentRepository.findByClosedAtIsNullOrderByOpenedAtDesc()
                .stream()
                .map(i -> new IncidentSummary(i.getSubjectKey(), i.getSeverity(), i.getMessage(), i.getOpenedAt()))
                .toList();

        String lastAppCheckAt = appCheckRepository.findLatestOverall()
                .map(r -> r.getCheckedAt())
                .orElse(null);

        return new GlobalStatus(hosts, apps, incidents, lastAppCheckAt);
    }

    public GlobalStatus parseSnapshot(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, GlobalStatus.class);
        } catch (Exception e) {
            return null;
        }
    }

    private HostSummary toHostSummary(Host host) {
        List<CheckState> states = checkStateRepository.findBySubjectKeyStartingWith("host:" + host.getName() + ":");
        String worstState = states.stream()
                .map(CheckState::getState)
                .reduce("OK", (a, b) -> {
                    if ("CRIT".equals(a) || "CRIT".equals(b)) return "CRIT";
                    if ("WARN".equals(a) || "WARN".equals(b)) return "WARN";
                    return "OK";
                });

        Double lastTemp = states.stream()
                .filter(cs -> cs.getSubjectKey().endsWith(":cpu-temp"))
                .findFirst()
                .map(cs -> cs.getLastNumeric())
                .orElse(null);

        Integer diskPct = states.stream()
                .filter(cs -> cs.getSubjectKey().contains(":disk:"))
                .map(cs -> cs.getLastNumeric())
                .filter(v -> v != null)
                .mapToInt(Double::intValue)
                .max().stream().boxed().findFirst().orElse(null);

        Double memAvailablePct = states.stream()
                .filter(cs -> cs.getSubjectKey().endsWith(":mem"))
                .findFirst()
                .map(cs -> cs.getLastNumeric())
                .orElse(null);

        List<ServiceStatus> services = states.stream()
                .filter(cs -> cs.getSubjectKey().contains(":service:"))
                .map(cs -> {
                    String key = cs.getSubjectKey();
                    String name = key.substring(key.indexOf(":service:") + 9);
                    String state = parseServiceState(cs.getLastValue());
                    return new ServiceStatus(name, state);
                })
                .toList();

        return new HostSummary(host.getName(), host.getModel(), host.getState(), worstState,
                host.getLastReportAt(), host.getLastBootTime(), lastTemp, diskPct, memAvailablePct, services);
    }

    private AppSummary toAppSummary(SentinelProperties.AppConfig app) {
        String internalState = checkStateRepository.findById("app:" + app.getName() + ":internal")
                .map(cs -> cs.getState()).orElse("UNKNOWN");
        String externalState = checkStateRepository.findById("app:" + app.getName() + ":external")
                .map(cs -> cs.getState()).orElse(null);
        String certState = checkStateRepository.findById("app:" + app.getName() + ":cert")
                .map(cs -> cs.getState()).orElse(null);
        String worst = "CRIT".equals(internalState) || "CRIT".equals(externalState) || "CRIT".equals(certState) ? "CRIT"
                : "WARN".equals(internalState) || "WARN".equals(externalState) || "WARN".equals(certState) ? "WARN" : "OK";
        return new AppSummary(app.getName(), app.getHost(), worst, internalState, externalState, certState);
    }

    private String parseServiceState(String value) {
        // "solarinverter.service=active" → "active"
        if (value == null) return null;
        int idx = value.indexOf('=');
        return idx >= 0 ? value.substring(idx + 1) : value;
    }

    // ── Records de sortie ────────────────────────────────────────────────────

    public record GlobalStatus(
            List<HostSummary> hosts,
            List<AppSummary> apps,
            List<IncidentSummary> openIncidents,
            String lastAppCheckAt
    ) {}

    public record HostSummary(
            String name, String model, String hostState, String checkState,
            String lastReportAt, String lastBootTime,
            Double lastTemp, Integer diskPct, Double memAvailablePct,
            List<ServiceStatus> services
    ) {
        public String state() {
            if ("CRIT".equals(hostState) || "CRIT".equals(checkState)) return "CRIT";
            if ("WARN".equals(hostState) || "WARN".equals(checkState)) return "WARN";
            return "OK";
        }
    }

    public record ServiceStatus(String name, String state) {}

    public record AppSummary(
            String name, String host, String state,
            String internalState, String externalState, String certState
    ) {}

    public record IncidentSummary(
            String subjectKey, String severity, String message, String openedAt
    ) {}
}
