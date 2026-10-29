package lu.kaminski.sentinel.ack;

import lu.kaminski.sentinel.api.StatusService;
import lu.kaminski.sentinel.domain.AccessToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * GET  /ack/{token} — page de rapport / accusé de lecture
 * POST /ack/{token} — enregistrement de l'accusé (DIGEST uniquement)
 * Servi sur les deux ports (interne + public).
 * Le filtre PublicPortFilter autorise /ack/** sur le port public.
 */
@Controller
@RequestMapping("/ack")
@RequiredArgsConstructor
public class AckController {

    private final AccessTokenService  tokenService;
    private final StatusService       statusService;

    @GetMapping("/{token}")
    public String view(@PathVariable String token,
                       HttpServletRequest request,
                       HttpServletResponse response,
                       Model model) {

        applySecurityHeaders(response);

        Optional<AccessToken> opt = tokenService.find(token);
        if (opt.isEmpty()) {
            response.setStatus(404);
            return "error/404";
        }

        AccessToken at = opt.get();
        model.addAttribute("token", token);
        model.addAttribute("accessToken", at);
        model.addAttribute("isDigest", "DIGEST".equals(at.getType()));
        model.addAttribute("status", statusService.currentStatus());
        model.addAttribute("snapshot", statusService.parseSnapshot(at.getSnapshotJson()));

        return "ack";
    }

    @PostMapping("/{token}")
    public String acknowledge(@PathVariable String token,
                               HttpServletRequest request,
                               HttpServletResponse response) {

        Optional<AccessToken> opt = tokenService.find(token);
        if (opt.isEmpty() || !"DIGEST".equals(opt.get().getType())) {
            response.setStatus(404);
            return "error/404";
        }

        tokenService.acknowledge(opt.get(), getClientIp(request));
        return "redirect:/ack/" + token;
    }

    private void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; style-src 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
