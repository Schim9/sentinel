package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.config.SentinelProperties.Thresholds;
import lu.kaminski.sentinel.report.ReportPayload;
import lu.kaminski.sentinel.report.ReportPayload.DiskInfo;
import lu.kaminski.sentinel.report.ReportPayload.ServiceInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Évalue toutes les règles sur un rapport entrant.
 * Produit une liste de RuleResult (un par sujet surveillé).
 */
@Component
@RequiredArgsConstructor
public class RuleEngine {

    private final SentinelProperties props;

    public List<RuleResult> evaluate(ReportPayload p) {
        String host = p.host();
        Thresholds t = props.effectiveThresholdsFor(host);
        List<RuleResult> results = new ArrayList<>();

        // ── Température CPU ──────────────────────────────────────────────────
        if (p.cpuTempC() != null) {
            String key = "host:" + host + ":cpu-temp";
            State s = State.OK;
            if (p.cpuTempC() >= t.getCpuTempCrit())      s = State.CRIT;
            else if (p.cpuTempC() >= t.getCpuTempWarn()) s = State.WARN;
            results.add(new RuleResult(key, s, String.format("%.1f°C", p.cpuTempC()), p.cpuTempC()));
        }

        // ── Throttling actuel ────────────────────────────────────────────────
        if (p.throttledRaw() != null) {
            String key = "host:" + host + ":throttle-current";
            State s = State.OK;
            try {
                long raw = Long.decode(p.throttledRaw());
                long current = raw & 0x000FL;
                if ((current & 0x1L) != 0 || (current & 0x4L) != 0) {
                    s = State.CRIT;  // sous-tension ou throttling actif
                } else if ((current & 0x2L) != 0 || (current & 0x8L) != 0) {
                    s = State.WARN;  // fréquence réduite ou limite douce
                }
            } catch (NumberFormatException ignored) {}
            results.add(new RuleResult(key, s, "throttled=" + p.throttledRaw()));
        }

        // ── Disques ──────────────────────────────────────────────────────────
        if (p.disks() != null) {
            for (DiskInfo disk : p.disks()) {
                String key = "host:" + host + ":disk:" + disk.mount();
                State s = State.OK;
                if (disk.usedPct() >= t.getDiskCritPct())      s = State.CRIT;
                else if (disk.usedPct() >= t.getDiskWarnPct()) s = State.WARN;
                results.add(new RuleResult(key, s,
                        String.format("%s %d%%", disk.mount(), disk.usedPct()), (double) disk.usedPct()));
            }
        }

        // ── Racine en lecture seule ──────────────────────────────────────────
        if (Boolean.TRUE.equals(p.rootReadOnly())) {
            results.add(new RuleResult("host:" + host + ":disk-ro", State.CRIT, "/ en lecture seule"));
        } else if (p.rootReadOnly() != null) {
            results.add(new RuleResult("host:" + host + ":disk-ro", State.OK, "/ rw"));
        }

        // ── Mémoire disponible ───────────────────────────────────────────────
        if (p.memTotalKb() != null && p.memAvailableKb() != null) {
            double pct = p.memAvailablePct();
            String key = "host:" + host + ":mem";
            State s = State.OK;
            if (pct < t.getMemAvailableCritPct())      s = State.CRIT;
            else if (pct < t.getMemAvailableWarnPct()) s = State.WARN;
            results.add(new RuleResult(key, s, String.format("%.1f%% dispo", pct), pct));
        }

        // ── Swap ─────────────────────────────────────────────────────────────
        if (p.swapTotalKb() != null && p.swapTotalKb() > 0) {
            double pct = p.swapUsedPct();
            String key = "host:" + host + ":swap";
            State s = State.OK;
            if (pct >= t.getSwapUsedCritPct())      s = State.CRIT;
            else if (pct >= t.getSwapUsedWarnPct()) s = State.WARN;
            results.add(new RuleResult(key, s, String.format("%.1f%% utilisé", pct), pct));
        }

        // ── Load ─────────────────────────────────────────────────────────────
        if (p.load1() != null && p.cpuCores() != null && p.cpuCores() > 0) {
            double warnThreshold = t.getLoadWarnPerCore() * p.cpuCores();
            double critThreshold = t.getLoadCritPerCore() * p.cpuCores();
            String key = "host:" + host + ":load";
            State s = State.OK;
            if (p.load1() > critThreshold)      s = State.CRIT;
            else if (p.load1() > warnThreshold) s = State.WARN;
            results.add(new RuleResult(key, s, String.format("load1=%.2f/%d cœurs", p.load1(), p.cpuCores()), p.load1()));
        }

        // ── Services attendus ────────────────────────────────────────────────
        if (p.services() != null) {
            for (ServiceInfo svc : p.services()) {
                String key = "host:" + host + ":service:" + svc.name();
                boolean active = "active".equals(svc.state());
                results.add(new RuleResult(key,
                        active ? State.OK : State.CRIT,
                        svc.name() + "=" + svc.state()));
            }
        }

        // ── Unités en échec ──────────────────────────────────────────────────
        if (p.failedUnits() != null) {
            String key = "host:" + host + ":failed-units";
            State s = p.failedUnits().isEmpty() ? State.OK : State.WARN;
            results.add(new RuleResult(key, s,
                    p.failedUnits().isEmpty() ? "aucune" : String.join(", ", p.failedUnits())));
        }

        // ── NTP ──────────────────────────────────────────────────────────────
        if (p.ntpSynced() != null) {
            String key = "host:" + host + ":ntp";
            results.add(new RuleResult(key,
                    Boolean.TRUE.equals(p.ntpSynced()) ? State.OK : State.WARN,
                    "ntpSynced=" + p.ntpSynced()));
        }

        // ── Erreurs I/O noyau (mode --full uniquement) ───────────────────────
        if (p.daily() != null && p.daily().kernelIoErrors24h() != null) {
            String key = "host:" + host + ":kernel-io-errors";
            int count = p.daily().kernelIoErrors24h();
            State s = State.OK;
            if (count >= 20)    s = State.CRIT;
            else if (count >= 1) s = State.WARN;
            results.add(new RuleResult(key, s, count + " erreur(s) I/O", (double) count));
        }

        return results;
    }
}
