package lu.kaminski.sentinel.appcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import lu.kaminski.sentinel.config.SentinelProperties;
import lu.kaminski.sentinel.evaluation.RuleResult;
import lu.kaminski.sentinel.evaluation.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppCheckServiceTest {

    WireMockServer wireMock;

    @Mock
    AppCheckRepository appCheckRepository;

    Clock clock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        clock = Clock.fixed(Instant.parse("2025-01-01T08:00:00Z"), ZoneOffset.UTC);
        when(appCheckRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private AppCheckService buildService(SentinelProperties.AppConfig... apps) {
        SentinelProperties props = new SentinelProperties();
        props.setApps(List.of(apps));
        return new AppCheckService(props, appCheckRepository, clock, new ObjectMapper());
    }

    private SentinelProperties.AppConfig app(String internalPath) {
        SentinelProperties.AppConfig app = new SentinelProperties.AppConfig();
        app.setName("myapp");
        app.setHost("testhost");
        app.setInternalUrl("http://localhost:" + wireMock.port() + internalPath);
        return app;
    }

    // ── Diagnostic croisé ─────────────────────────────────────────────────────

    @Test
    void internal_ok_external_ok_proxy_ok() {
        wireMock.stubFor(get(urlEqualTo("/int/status")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(get(urlEqualTo("/ext/status")).willReturn(aResponse().withStatus(200)));

        SentinelProperties.AppConfig a = app("/int/status");
        a.setExternalUrl("http://localhost:" + wireMock.port() + "/ext/status");

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:proxy");
            assertThat(r.state()).isEqualTo(State.OK);
        });
    }

    @Test
    void internal_ok_external_ko_proxy_warn() {
        wireMock.stubFor(get(urlEqualTo("/int/status")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(get(urlEqualTo("/ext/status")).willReturn(aResponse().withStatus(503)));

        SentinelProperties.AppConfig a = app("/int/status");
        a.setExternalUrl("http://localhost:" + wireMock.port() + "/ext/status");

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:internal");
            assertThat(r.state()).isEqualTo(State.OK);
        });
        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:external");
            assertThat(r.state()).isEqualTo(State.CRIT);
        });
        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:proxy");
            assertThat(r.state()).isEqualTo(State.WARN);
        });
    }

    @Test
    void internal_ko_external_ok_proxy_warn() {
        wireMock.stubFor(get(urlEqualTo("/int/status")).willReturn(aResponse().withStatus(503)));
        wireMock.stubFor(get(urlEqualTo("/ext/status")).willReturn(aResponse().withStatus(200)));

        SentinelProperties.AppConfig a = app("/int/status");
        a.setExternalUrl("http://localhost:" + wireMock.port() + "/ext/status");

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:proxy");
            assertThat(r.state()).isEqualTo(State.WARN);
        });
    }

    // ── parseStatusBody ───────────────────────────────────────────────────────

    @Test
    void parse_status_up_returns_ok() {
        wireMock.stubFor(get(urlEqualTo("/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"up\"}")));

        SentinelProperties.AppConfig a = app("/status");
        a.setParseStatusBody(true);

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:app-status");
            assertThat(r.state()).isEqualTo(State.OK);
        });
    }

    @Test
    void parse_status_down_returns_crit() {
        wireMock.stubFor(get(urlEqualTo("/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"down\"}")));

        SentinelProperties.AppConfig a = app("/status");
        a.setParseStatusBody(true);

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:app-status");
            assertThat(r.state()).isEqualTo(State.CRIT);
        });
    }

    @Test
    void parse_status_anomalies_present_returns_warn() {
        wireMock.stubFor(get(urlEqualTo("/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"up\",\"anomalies\":[\"disk nearly full\"]}")));

        SentinelProperties.AppConfig a = app("/status");
        a.setParseStatusBody(true);

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:anomalies");
            assertThat(r.state()).isEqualTo(State.WARN);
        });
    }

    @Test
    void parse_status_empty_anomalies_returns_ok() {
        wireMock.stubFor(get(urlEqualTo("/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"up\",\"anomalies\":[]}")));

        SentinelProperties.AppConfig a = app("/status");
        a.setParseStatusBody(true);

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:anomalies");
            assertThat(r.state()).isEqualTo(State.OK);
        });
    }

    // ── Comportement de base ──────────────────────────────────────────────────

    @Test
    void internal_ko_no_external_returns_crit() {
        wireMock.stubFor(get(urlEqualTo("/status")).willReturn(aResponse().withStatus(503)));

        List<RuleResult> results = buildService(app("/status")).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:internal");
            assertThat(r.state()).isEqualTo(State.CRIT);
        });
        // Pas de check externe → pas de check proxy
        assertThat(results).noneMatch(r -> r.subjectKey().equals("app:myapp:proxy"));
    }

    @Test
    void expect_body_contains_mismatch_returns_crit() {
        wireMock.stubFor(get(urlEqualTo("/status"))
                .willReturn(aResponse().withStatus(200).withBody("unexpected response")));

        SentinelProperties.AppConfig a = app("/status");
        a.setExpectBodyContains("\"status\":\"OK\"");

        List<RuleResult> results = buildService(a).checkAll();

        assertThat(results).anySatisfy(r -> {
            assertThat(r.subjectKey()).isEqualTo("app:myapp:internal");
            assertThat(r.state()).isEqualTo(State.CRIT);
        });
    }
}
