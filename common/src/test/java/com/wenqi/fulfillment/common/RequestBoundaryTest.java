package com.wenqi.fulfillment.common;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.*;

class RequestBoundaryTest {
  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void unauthenticatedBusinessCallsRejectedButHealthRemainsAvailable() throws Exception {
    var filter = new RequestIdentityFilter("a".repeat(48), mapper);
    var called = new AtomicBoolean();
    var denied = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/v1/orders"), denied, (a, b) -> called.set(true));
    assertEquals(401, denied.getStatus());
    assertFalse(called.get());
    filter.doFilter(
        new MockHttpServletRequest("GET", "/actuator/health/readiness"),
        new MockHttpServletResponse(),
        (a, b) -> called.set(true));
    assertTrue(called.get());
  }

  @Test
  void authorizedBodyIsPreservedAndRequestIdCannotInjectLogs() throws Exception {
    var filter = new RequestIdentityFilter("a".repeat(48), mapper);
    var req = new MockHttpServletRequest("POST", "/v1/orders");
    req.addHeader("X-API-Key", "a".repeat(48));
    req.addHeader("X-Request-ID", "bad\r\nid");
    var response = new MockHttpServletResponse();
    filter.doFilter(req, response, (a, b) -> assertNotNull(org.slf4j.MDC.get("requestId")));
    assertTrue(response.getHeader("X-Request-ID").matches("[a-f0-9-]{36}"));
    assertNull(org.slf4j.MDC.get("requestId"));
  }

  @Test
  void chunkedOversizedBodyRejectedBeforeController() throws Exception {
    var req = new MockHttpServletRequest("POST", "/v1/orders");
    req.setContent(new byte[JsonBodyLimitFilter.MAX_BYTES + 1]);
    var response = new MockHttpServletResponse();
    new JsonBodyLimitFilter(mapper)
        .doFilter(req, response, (a, b) -> fail("Controller must not receive oversized body"));
    assertEquals(413, response.getStatus());
  }

  @Test
  void permittedBodyCanBeReadByController() throws Exception {
    var req = new MockHttpServletRequest("PUT", "/v1/items");
    req.setContent("{\"sku\":\"A\"}".getBytes());
    new JsonBodyLimitFilter(mapper)
        .doFilter(
            req,
            new MockHttpServletResponse(),
            (a, b) ->
                assertEquals("{\"sku\":\"A\"}", new String(a.getInputStream().readAllBytes())));
  }

  @Test
  void jsonRejectsFractionalIntegerUnknownFieldsAndDuplicateKeys() {
    var builder = new Jackson2ObjectMapperBuilder();
    new PlatformConfiguration().strictJson().customize(builder);
    ObjectMapper strict = builder.build();
    assertThrows(
        Exception.class,
        () -> strict.readValue("{\"sku\":\"a\",\"quantity\":1.5}", Api.LineItem.class));
    assertThrows(
        Exception.class,
        () ->
            strict.readValue("{\"sku\":\"a\",\"quantity\":1,\"extra\":true}", Api.LineItem.class));
    assertThrows(
        Exception.class,
        () ->
            strict.readValue("{\"sku\":\"a\",\"quantity\":1,\"quantity\":2}", Api.LineItem.class));
  }
}
