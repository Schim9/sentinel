package lu.kaminski.sentinel.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String VALID_PAYLOAD = """
            {
              "schemaVersion": 1,
              "host": "pi-backend-1",
              "mode": "normal",
              "collectedAt": "2026-10-18T10:00:00Z",
              "cpuTempC": 55.0,
              "throttledRaw": "0x0",
              "load1": 0.5, "load5": 0.4, "load15": 0.3, "cpuCores": 4,
              "uptimeSec": 86400,
              "bootTime": "2026-10-17T10:00:00",
              "memTotalKb": 948280, "memAvailableKb": 500000,
              "swapTotalKb": 102396, "swapFreeKb": 90000,
              "disks": [{"mount": "/", "totalKb": 30000000, "usedKb": 10000000, "usedPct": 33}],
              "rootReadOnly": false,
              "services": [],
              "failedUnits": [],
              "ntpSynced": true,
              "topProcesses": [],
              "powerW": null,
              "daily": null
            }
            """;

    @Test
    void validReport_returns202() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Monitor-Token", "test-token-pi-backend-1")
                        .content(VALID_PAYLOAD))
                .andExpect(status().isAccepted());
    }

    @Test
    void invalidToken_returns401() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Monitor-Token", "wrong-token")
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingToken_returns401() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownHost_returns404() throws Exception {
        String payload = VALID_PAYLOAD.replace("pi-backend-1", "unknown-host");
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Monitor-Token", "any-token")
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidJson_returns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Monitor-Token", "test-token")
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }
}
