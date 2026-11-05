package com.company.skillplatform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.agent.application.AgentRuntimeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DshAgentRuntimeClientTest {
    @Test void cancelsCreatedRunWhenEventStreamEndsWithoutTerminalEvent() throws Exception {
        AtomicInteger cancels=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/v1/runs", exchange->{
            String path=exchange.getRequestURI().getPath();
            int status=path.endsWith("/events")?200:path.endsWith("/cancel")?202:202;
            if(path.endsWith("/cancel"))cancels.incrementAndGet();
            byte[] body=path.endsWith("/events")?"event: message.delta\ndata: {\"text\":\"hello\"}\n\n".getBytes(StandardCharsets.UTF_8):"{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",path.endsWith("/events")?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        try {
            var client=new DshAgentRuntimeClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test",Duration.ofSeconds(5));
            var request=new AgentRuntimeClient.RunRequest("run-1","session-1",List.of(),"token",null,null,true,Map.of());
            assertThatThrownBy(()->client.run(request,event->{})).hasMessageContaining("DSH runtime unavailable");
            assertThat(cancels).hasValue(1);
        } finally {server.stop(0);}
    }
}
