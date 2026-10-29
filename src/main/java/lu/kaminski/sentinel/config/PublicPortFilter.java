package lu.kaminski.sentinel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Sur le port public (8091), seuls /ack/** et /public-assets/** sont autorisés.
 * Toute autre route → 404 avec corps vide (ne rien révéler).
 */
@Component
@Order(Integer.MIN_VALUE)
public class PublicPortFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_PREFIXES = Set.of("/ack/", "/public-assets/", "/report", "/api/status");

    private final int publicPort;

    public PublicPortFilter(SentinelProperties props) {
        this.publicPort = props.getPublicPort();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getLocalPort() == publicPort && !isAllowed(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return; // corps vide
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String uri) {
        return ALLOWED_PREFIXES.stream().anyMatch(uri::startsWith);
    }
}
