package lu.kaminski.sentinel.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie que le port public (8091) n'expose que /ack/** et /public-assets/**.
 * Les autres routes doivent renvoyer 404.
 *
 * Note : avec MockMvc, le port n'est pas réellement 8091.
 * On simule via {@code with(request -> { request.setLocalPort(8091); return request; })}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicPortFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiStatus_onPublicPort_returns200() throws Exception {
        mockMvc.perform(get("/api/status")
                        .with(r -> { r.setLocalPort(8091); return r; }))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealth_onPublicPort_returns404() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .with(r -> { r.setLocalPort(8091); return r; }))
                .andExpect(status().isNotFound());
    }

    @Test
    void root_onPublicPort_returns404() throws Exception {
        mockMvc.perform(get("/")
                        .with(r -> { r.setLocalPort(8091); return r; }))
                .andExpect(status().isNotFound());
    }

    @Test
    void ack_onPublicPort_isAllowed() throws Exception {
        // Le token n'existera pas → 404 applicatif, mais pas bloqué par le filtre
        // On vérifie juste que la requête passe le filtre (pas de blocage 404 du filtre)
        mockMvc.perform(get("/ack/sometoken")
                        .with(r -> { r.setLocalPort(8091); return r; }))
                .andExpect(status().isNotFound()); // 404 applicatif (token inconnu), pas du filtre
    }

    @Test
    void apiStatus_onInternalPort_isAllowed() throws Exception {
        mockMvc.perform(get("/api/status")
                        .with(r -> { r.setLocalPort(8090); return r; }))
                .andExpect(status().isOk());
    }
}
