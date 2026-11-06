package com.company.skillplatform.common.logging;

import java.util.Map;
import org.slf4j.MDC;

/** Restores the caller's MDC after nested or asynchronous work. */
public final class YanshuLogContextScope implements AutoCloseable {
 private final Map<String,String> previous;
 private YanshuLogContextScope(YanshuLogModule module){previous=MDC.getCopyOfContextMap();MDC.put("module",module.name());}
 public static YanshuLogContextScope module(YanshuLogModule module){return new YanshuLogContextScope(module);}
 @Override public void close(){if(previous==null)MDC.clear();else MDC.setContextMap(previous);}
}
