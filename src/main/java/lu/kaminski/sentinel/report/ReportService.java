package lu.kaminski.sentinel.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.domain.Host;
import lu.kaminski.sentinel.domain.Report;
import lu.kaminski.sentinel.evaluation.CheckStateService;
import lu.kaminski.sentinel.evaluation.EvaluationHealthIndicator;
import lu.kaminski.sentinel.evaluation.RuleEngine;
import lu.kaminski.sentinel.evaluation.StateTransition;
import lu.kaminski.sentinel.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository          reportRepository;
    private final HostRepository            hostRepository;
    private final RuleEngine                ruleEngine;
    private final CheckStateService         checkStateService;
    private final NotificationService       notificationService;
    private final EvaluationHealthIndicator evaluationHealthIndicator;
    private final SentinelProperties        props;
    private final ObjectMapper              objectMapper;
    private final Clock                     clock;

    @Transactional
    public void process(ReportPayload payload) {
        String now = Instant.now(clock).toString();

        // 1. Persistance du rapport
        Report report = buildReport(payload, now);
        reportRepository.save(report);

        // 2. Mise à jour de l'hôte
        String model = props.findHost(payload.host())
                .map(SentinelProperties.HostConfig::getModel).orElse(null);
        Host host = hostRepository.findById(payload.host())
                .orElseGet(() -> new Host(payload.host(), model));
        if (host.getModel() == null && model != null) host.setModel(model);
        boolean reboot = host.getLastBootTime() != null
                && payload.bootTime() != null
                && !payload.bootTime().equals(host.getLastBootTime());
        host.setLastReportAt(now);
        host.setLastBootTime(payload.bootTime());
        hostRepository.save(host);

        // 3. Évaluation des règles
        List<StateTransition> transitions = checkStateService.evaluate(
                ruleEngine.evaluate(payload), now);

        // Nettoyage des services retirés de l'agent
        List<String> reportedServices = payload.services() != null
                ? payload.services().stream().map(ReportPayload.ServiceInfo::name).toList()
                : List.of();
        checkStateService.closeRemovedServices(payload.host(), reportedServices, now);

        // 4. Événement reboot
        if (reboot) {
            log.info("Reboot détecté sur {} à {}", payload.host(), payload.bootTime());
            notificationService.notifyReboot(payload.host(), payload.bootTime());
        }

        // 5. Notification des transitions
        if (!transitions.isEmpty()) {
            notificationService.enqueue(transitions);
        }

        evaluationHealthIndicator.recordEvaluation();
    }

    private Report buildReport(ReportPayload p, String receivedAt) {
        Report r = new Report();
        r.setHost(p.host());
        r.setReceivedAt(receivedAt);
        r.setCollectedAt(p.collectedAt() != null ? p.collectedAt() : receivedAt);
        r.setMode(p.mode() != null ? p.mode() : "normal");
        r.setCpuTempC(p.cpuTempC());
        r.setMemAvailablePct(p.memAvailablePct());
        r.setSwapUsedPct(p.swapUsedPct());
        r.setMaxDiskPct(p.maxDiskPct());
        r.setLoad1(p.load1());
        r.setThrottledRaw(p.throttledRaw());
        try {
            r.setPayloadJson(objectMapper.writeValueAsString(p));
        } catch (JsonProcessingException e) {
            r.setPayloadJson("{}");
        }
        return r;
    }
}
