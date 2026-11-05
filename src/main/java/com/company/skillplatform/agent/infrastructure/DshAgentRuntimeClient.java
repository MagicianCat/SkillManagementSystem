package com.company.skillplatform.agent.infrastructure;

import com.company.skillplatform.agent.application.AgentRuntimeClient;
import com.company.skillplatform.common.application.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DshAgentRuntimeClient implements AgentRuntimeClient {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DshAgentRuntimeClient.class);
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json; private final String baseUrl; private final String token; private final Duration timeout;
    public DshAgentRuntimeClient(ObjectMapper json,@Value("${agent.runtime.base-url:http://127.0.0.1:3090}")String baseUrl,
            @Value("${agent.runtime.service-token:${DSH_SERVICE_TOKEN:local-dsh-service-token}}")String token,
            @Value("${agent.runtime.timeout:PT130S}")Duration timeout){this.json=json;this.baseUrl=baseUrl;this.token=token;this.timeout=timeout;}
    @Override public void run(RunRequest request,Consumer<RuntimeEvent>listener){
        String phase="create_request";
        boolean runCreated=false, terminal=false;
        try{
            HttpRequest create=HttpRequest.newBuilder(URI.create(baseUrl+"/internal/v1/runs")).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
            phase="create_send";
            HttpResponse<String> created=http.send(create,HttpResponse.BodyHandlers.ofString());
            phase="create_validate";
            if(created.statusCode()!=202){log.warn("event=agent.dsh.create_rejected runKey={} httpStatus={} response={}",request.runId(),created.statusCode(),safeResponse(created.body()));throw new IOException("DSH create returned HTTP "+created.statusCode());}
            runCreated=true;
            phase="events_send";
            HttpRequest events=HttpRequest.newBuilder(URI.create(baseUrl+"/internal/v1/runs/"+request.runId()+"/events")).timeout(timeout)
                    .header("Authorization","Bearer "+token).header("Accept","text/event-stream").GET().build();
            HttpResponse<InputStream> stream=http.send(events,HttpResponse.BodyHandlers.ofInputStream());
            phase="events_validate";
            if(stream.statusCode()!=200){log.warn("event=agent.dsh.events_rejected runKey={} httpStatus={}",request.runId(),stream.statusCode());throw new IOException("DSH events returned HTTP "+stream.statusCode());}
            phase="events_stream";
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream.body()))){
                String eventType=null;StringBuilder data=new StringBuilder();String line;
                while((line=reader.readLine())!=null){
                    if(line.isEmpty()){if(eventType!=null&&data.length()>0){if(Set.of("runtime.completed","runtime.failed","runtime.cancelled").contains(eventType))terminal=true;listener.accept(new RuntimeEvent(eventType,json.readValue(data.toString(),new TypeReference<>(){})));}eventType=null;data.setLength(0);}
                    else if(line.startsWith("event:"))eventType=line.substring(6).trim();
                    else if(line.startsWith("data:"))data.append(line.substring(5).trim());
                }
            }
            if(!terminal)throw new IOException("DSH event stream ended before a terminal event");
        }catch(InterruptedException e){Thread.interrupted();if(runCreated&&!terminal)cancelAbandoned(request.runId());Thread.currentThread().interrupt();log.warn("event=agent.dsh.transport_failed runKey={} phase={} target={} causeType=InterruptedException",request.runId(),phase,safeTarget());throw unavailable("DSH request interrupted",e);}
        catch(Exception e){if(runCreated&&!terminal)cancelAbandoned(request.runId());Throwable root=e;while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();log.warn("event=agent.dsh.transport_failed runKey={} phase={} target={} causeType={} rootCauseType={}",request.runId(),phase,safeTarget(),e.getClass().getSimpleName(),root.getClass().getSimpleName());throw unavailable("DSH runtime unavailable",e);}
    }
    @Override public void cancel(String runtimeRunId){
        try{HttpRequest req=HttpRequest.newBuilder(URI.create(baseUrl+"/internal/v1/runs/"+runtimeRunId+"/cancel")).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build();http.send(req,HttpResponse.BodyHandlers.discarding());}
        catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception ignored){}
    }
    private BusinessException unavailable(String message,Exception cause){return new BusinessException("AGENT_RUNTIME_UNAVAILABLE",message,HttpStatus.SERVICE_UNAVAILABLE);}
    private String safeResponse(String body){
        try{var node=json.readTree(body);String value=node.path("message").asText(node.path("code").asText("unknown"));return value.matches("[A-Za-z0-9_. -]{1,160}")?value:"redacted";}
        catch(Exception ignored){return "unparseable";}
    }
    private String safeTarget(){try{URI uri=URI.create(baseUrl);return uri.getHost()+":"+uri.getPort();}catch(Exception ignored){return "invalid-url";}}
    private void cancelAbandoned(String runId){
        try{HttpRequest request=HttpRequest.newBuilder(URI.create(baseUrl+"/internal/v1/runs/"+runId+"/cancel"))
                .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
            log.warn("event=agent.dsh.abandoned_cancel runKey={} httpStatus={}",runId,status);
        }catch(Exception e){log.warn("event=agent.dsh.abandoned_cancel_failed runKey={} causeType={}",runId,e.getClass().getSimpleName());}
    }
}
