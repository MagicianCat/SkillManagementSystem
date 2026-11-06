package com.company.skillplatform.common.application;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Stable classifications only; exception messages may contain credentials or URLs. */
public final class ErrorClassifier {
 private ErrorClassifier(){}
 public record Classified(String code,String rootCauseType,boolean retryable){}
 public static Classified classify(Throwable failure,String fallback){
  Throwable root=failure;while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
  String code=root instanceof SocketTimeoutException||root instanceof java.net.http.HttpTimeoutException?"UPSTREAM_TIMEOUT":
   root instanceof UnknownHostException?"DNS_FAILURE":root instanceof ConnectException?"CONNECTION_REFUSED":
   root instanceof SSLException?"TLS_FAILURE":fallback;
  return new Classified(code,root.getClass().getSimpleName(),code.equals("UPSTREAM_TIMEOUT")||code.equals("DNS_FAILURE")||code.equals("CONNECTION_REFUSED"));
 }
}
