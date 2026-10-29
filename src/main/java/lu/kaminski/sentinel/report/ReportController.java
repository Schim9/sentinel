package lu.kaminski.sentinel.report;

import lu.kaminski.sentinel.config.SentinelProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lu.kaminski.sentinel.ack.AccessTokenService;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final SentinelProperties props;
    private final ReportService reportService;

    public ReportController(SentinelProperties props, ReportService reportService) {
        this.props         = props;
        this.reportService = reportService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Monitor-Token", required = false) String token,
            @RequestBody(required = false) ReportPayload payload) {

        if (payload == null) {
            return ResponseEntity.badRequest().build();
        }

        String hostName = payload.host();
        if (hostName == null || hostName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Vérification que l'hôte est connu
        if (props.findHost(hostName).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Vérification du token (comparaison hachée en temps constant)
        String expectedToken = props.hostToken(hostName).orElse(null);
        if (!isValidToken(token, expectedToken)) {
            return ResponseEntity.status(401).build();
        }

        reportService.process(payload);
        return ResponseEntity.accepted().build();
    }

    private boolean isValidToken(String incoming, String expected) {
        return AccessTokenService.timingSafeEquals(incoming, expected);
    }
}
