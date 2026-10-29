package lu.kaminski.sentinel.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Représentation du JSON envoyé par sentinel-report.sh.
 * Les champs sont null si la métrique n'était pas disponible sur le Pi.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportPayload(
        int schemaVersion,
        String host,
        String mode,
        String collectedAt,
        Double cpuTempC,
        String throttledRaw,
        Double load1,
        Double load5,
        Double load15,
        Integer cpuCores,
        Long uptimeSec,
        String bootTime,
        Long memTotalKb,
        Long memAvailableKb,
        Long swapTotalKb,
        Long swapFreeKb,
        List<DiskInfo> disks,
        Boolean rootReadOnly,
        List<ServiceInfo> services,
        List<String> failedUnits,
        Boolean ntpSynced,
        List<ProcessInfo> topProcesses,
        Double powerW,
        DailyMetrics daily
) {

    public record DiskInfo(String mount, long totalKb, long usedKb, int usedPct) {}

    public record ServiceInfo(String name, String state) {}

    public record ProcessInfo(String name, long rssKb) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyMetrics(
            Integer aptUpgradable,
            Integer kernelIoErrors24h,
            String osRelease,
            String kernel
    ) {}

    public double memAvailablePct() {
        if (memTotalKb == null || memTotalKb == 0 || memAvailableKb == null) return 100.0;
        return 100.0 * memAvailableKb / memTotalKb;
    }

    public double swapUsedPct() {
        if (swapTotalKb == null || swapTotalKb == 0 || swapFreeKb == null) return 0.0;
        return 100.0 * (swapTotalKb - swapFreeKb) / swapTotalKb;
    }

    public double maxDiskPct() {
        if (disks == null || disks.isEmpty()) return 0.0;
        return disks.stream().mapToDouble(DiskInfo::usedPct).max().orElse(0.0);
    }
}
