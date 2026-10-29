package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.report.ReportPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private RuleEngine ruleEngine;
    private SentinelProperties props;

    @BeforeEach
    void setUp() {
        props = new SentinelProperties();
        props.setPublicPort(8091);
        props.setPublicBaseUrl("https://sentinel.kaminski.lu");

        SentinelProperties.HostConfig host = new SentinelProperties.HostConfig();
        host.setName("pi-backend-1");
        host.setModel("pi3");
        host.setIp("192.168.0.11");
        props.setHosts(List.of(host));
        props.setApps(List.of());

        ruleEngine = new RuleEngine(props);
    }

    @Test
    void cpuTemp_ok() {
        ReportPayload p = payloadWithTemp(60.0);
        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:cpu-temp", State.OK);
    }

    @Test
    void cpuTemp_warn() {
        ReportPayload p = payloadWithTemp(72.0);
        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:cpu-temp", State.WARN);
    }

    @Test
    void cpuTemp_crit() {
        ReportPayload p = payloadWithTemp(85.0);
        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:cpu-temp", State.CRIT);
    }

    @Test
    void cpuTemp_hostOverride() {
        SentinelProperties.HostConfig pi5 = new SentinelProperties.HostConfig();
        pi5.setName("pi5");
        pi5.setModel("pi5");
        pi5.setIp("192.168.0.52");
        SentinelProperties.Thresholds override = new SentinelProperties.Thresholds();
        override.setCpuTempWarn(75.0);
        override.setCpuTempCrit(85.0);
        pi5.setThresholds(override);
        props.setHosts(List.of(pi5));

        // 72°C → WARN sur pi3, mais OK sur pi5 (seuil à 75)
        ReportPayload p = new ReportPayload(1, "pi5", "normal", null,
                72.0, null, null, null, null, 4,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertState(ruleEngine.evaluate(p), "host:pi5:cpu-temp", State.OK);
    }

    @Test
    void disk_warn() {
        ReportPayload p = payloadWithDisk(82);
        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:disk:/", State.WARN);
    }

    @Test
    void disk_crit() {
        ReportPayload p = payloadWithDisk(92);
        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:disk:/", State.CRIT);
    }

    @Test
    void rootReadOnly_isCrit() {
        ReportPayload p = minimalPayload("pi-backend-1");
        p = new ReportPayload(1, "pi-backend-1", "normal", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, true, null, null, null, null, null, null);

        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:disk-ro", State.CRIT);
    }

    @Test
    void throttling_current_undervoltage_isCrit() {
        ReportPayload p = new ReportPayload(1, "pi-backend-1", "normal", null,
                null, "0x1", null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:throttle-current", State.CRIT);
    }

    @Test
    void service_inactive_isCrit() {
        ReportPayload.ServiceInfo svc = new ReportPayload.ServiceInfo("solarinverter.service", "failed");
        ReportPayload p = new ReportPayload(1, "pi-backend-1", "normal", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, List.of(svc), null, null, null, null, null);

        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:service:solarinverter.service", State.CRIT);
    }

    @Test
    void ntpNotSynced_isWarn() {
        ReportPayload p = new ReportPayload(1, "pi-backend-1", "normal", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, false, null, null, null);

        assertState(ruleEngine.evaluate(p), "host:pi-backend-1:ntp", State.WARN);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertState(List<RuleResult> results, String key, State expected) {
        assertThat(results)
                .filteredOn(r -> r.subjectKey().equals(key))
                .singleElement()
                .satisfies(r -> assertThat(r.state()).isEqualTo(expected));
    }

    private ReportPayload payloadWithTemp(double temp) {
        return new ReportPayload(1, "pi-backend-1", "normal", null,
                temp, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private ReportPayload payloadWithDisk(int pct) {
        ReportPayload.DiskInfo disk = new ReportPayload.DiskInfo("/", 30_000_000L, (long)(30_000_000 * pct / 100), pct);
        return new ReportPayload(1, "pi-backend-1", "normal", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                List.of(disk), null, null, null, null, null, null, null);
    }

    private ReportPayload minimalPayload(String host) {
        return new ReportPayload(1, host, "normal", null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
