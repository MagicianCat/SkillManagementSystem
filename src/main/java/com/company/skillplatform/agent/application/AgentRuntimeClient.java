package com.company.skillplatform.agent.application;
import java.util.*;
import java.util.function.Consumer;
public interface AgentRuntimeClient {
 record Message(String role,String content){}
 record RunRequest(String runId,String sessionId,List<Message>messages,String mcpToken,String platform,String osType,boolean firstTurn){}
 record RuntimeEvent(String type,Map<String,Object>data){}
 void run(RunRequest request, Consumer<RuntimeEvent> listener);
 void cancel(String runtimeRunId);
}
