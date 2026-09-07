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
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json; private final String baseUrl; private final String token; private final Duration timeout;
    public DshAgentRuntimeClient(ObjectMapper json,@Value("${agent.runtime.base-url:http://127.0.0.1:3090}")String baseUrl,
            @Value("${agent.runtime.service-token:${DSH_SERVICE_TOKEN:local-dsh-service-token}}")String token,
            @Value("${agent.runtime.timeout:PT130S}")Duration timeout){this.json=json;this.baseUrl=baseUrl;this.token=token;this.timeout=timeout;}
    @Override public void run(RunRequest request,Consumer<RuntimeEvent>listener){
        try{
            HttpRequest create=HttpRequest.newBuilder(URI.create(baseUrl+"/internal/v1/runs")).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
            HttpResponse<String> created=http.send(create,HttpResponse.BodyHandlers.ofString());
            if(created.statusCode()!=202)throw new IOException("DSH create returned HTTP "+created.statusCode());
            HttpRequest events=HttpRequest.newBuilder(URI.create(baseUrl+"/internal/v1/runs/"+request.runId()+"/events")).timeout(timeout)
                    .header("Authorization","Bearer "+token).header("Accept","text/event-stream").GET().build();
            HttpResponse<InputStream> stream=http.send(events,HttpResponse.BodyHandlers.ofInputStream());
            if(stream.statusCode()!=200)throw new IOException("DSH events returned HTTP "+stream.statusCode());
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream.body()))){
                String eventType=null;StringBuilder data=new StringBuilder();String line;
                while((line=reader.readLine())!=null){
                    if(line.isEmpty()){if(eventType!=null&&data.length()>0)listener.accept(new RuntimeEvent(eventType,json.readValue(data.toString(),new TypeReference<>(){})));eventType=null;data.setLength(0);}
                    else if(line.startsWith("event:"))eventType=line.substring(6).trim();
                    else if(line.startsWith("data:"))data.append(line.substring(5).trim());
                }
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable("DSH request interrupted",e);}
        catch(Exception e){throw unavailable("DSH runtime unavailable",e);}
    }
    @Override public void cancel(String runtimeRunId){
        try{HttpRequest req=HttpRequest.newBuilder(URI.create(baseUrl+"/internal/v1/runs/"+runtimeRunId+"/cancel")).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build();http.send(req,HttpResponse.BodyHandlers.discarding());}
        catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception ignored){}
    }
    private BusinessException unavailable(String message,Exception cause){return new BusinessException("AGENT_RUNTIME_UNAVAILABLE",message,HttpStatus.SERVICE_UNAVAILABLE);}
}
