package uz.kuanov.minibank.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 401 and 403 from the security filter chain happen before Spring MVC, so @RestControllerAdvice never sees them.
 * This hands them to MVC's exception resolver, so GlobalExceptionHandler renders them like every other error.
 */
final class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver exceptionResolver;

    ProblemDetailsSecurityHandler(HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        // RFC 6750: a 401 from a bearer-token resource server announces the expected scheme
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (exceptionResolver.resolveException(request, response, null, ex) == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (exceptionResolver.resolveException(request, response, null, ex) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
