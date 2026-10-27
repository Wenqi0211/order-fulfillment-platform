package com.wenqi.fulfillment.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds JSON allocation for both fixed-length and chunked request bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JsonBodyLimitFilter extends OncePerRequestFilter {
  static final int MAX_BYTES = 64 * 1024;
  private final ObjectMapper mapper;

  public JsonBodyLimitFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!java.util.Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
    if (body.length > MAX_BYTES) {
      response.setStatus(413);
      response.setContentType("application/json");
      mapper.writeValue(
          response.getOutputStream(),
          Map.of(
              "status", 413, "code", "BODY_TOO_LARGE", "detail", "Maximum request body is 64 KiB"));
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              @Override
              public int read() {
                return input.read();
              }

              @Override
              public int read(byte[] b, int offset, int length) {
                return input.read(b, offset, length);
              }

              @Override
              public boolean isFinished() {
                return input.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous request bodies only");
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
          }
        },
        response);
  }
}
