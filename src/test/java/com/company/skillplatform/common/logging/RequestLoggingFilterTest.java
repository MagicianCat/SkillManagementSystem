package com.company.skillplatform.common.logging;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
class RequestLoggingFilterTest {
 @Test void rejectsUntrustedIdAndRestoresCallerContext() throws Exception {
  MDC.put("outer","keep");var request=new MockHttpServletRequest("GET","/api/v1/test");request.addHeader("X-Request-Id","bad\nheader");var response=new MockHttpServletResponse();
  FilterChain chain=(req,res)->{assertEquals(response.getHeader("X-Request-Id"),MDC.get("requestId"));assertNotEquals("bad\nheader",MDC.get("requestId"));assertEquals(MDC.get("requestId"),MDC.get("traceId"));};
  new RequestLoggingFilter().doFilter(request,response,chain);
  assertEquals("keep",MDC.get("outer"));assertNull(MDC.get("requestId"));MDC.clear();
 }
}
