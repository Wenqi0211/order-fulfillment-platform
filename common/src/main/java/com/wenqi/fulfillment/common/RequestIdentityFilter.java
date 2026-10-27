package com.wenqi.fulfillment.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdentityFilter extends OncePerRequestFilter {
  private final byte[] apiKey;
  private final ObjectMapper mapper;

  public RequestIdentityFilter(@Value("${app.api-key}") String key, ObjectMapper mapper) {
    if (key == null || key.length() < 32 || key.contains("CHANGE_ME"))
      throw new IllegalArgumentException(
          "APP_API_KEY must be a generated secret of at least 32 characters");
    this.apiKey = key.getBytes(StandardCharsets.UTF_8);
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Request-ID");
    String requestId =
        supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    MDC.put("requestId", requestId);
    response.setHeader("X-Request-ID", requestId);
    response.setHeader("X-Content-Type-Options", "nosniff");
    try {
      String path = request.getRequestURI();
      boolean health = path.equals("/actuator/health") || path.startsWith("/actuator/health/");
      String candidate = request.getHeader("X-API-Key");
      if (!health
          && (candidate == null
              || !MessageDigest.isEqual(apiKey, candidate.getBytes(StandardCharsets.UTF_8)))) {
        response.setStatus(401);
        response.setContentType("application/json");
        mapper.writeValue(
            response.getOutputStream(),
            Map.of(
                "status",
                401,
                "code",
                "UNAUTHORIZED",
                "detail",
                "Valid service API key required",
                "requestId",
                requestId));
        return;
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }
}
