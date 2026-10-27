package com.company.skillplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.skillplatform.knowledge.domain.EmbeddingProviderException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpEmbeddingClientTest {
    @Test void preservesProviderQuotaCodeFromErrorResponse() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/embed",exchange->{
            byte[] body="{\"code\":\"provider_quota_exceeded\",\"message\":\"quota exhausted\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(400,body.length);
            try(var stream=exchange.getResponseBody()){stream.write(body);}
        });
        server.start();
        try{
            var properties=new KnowledgeProperties();
            properties.getEmbedding().setUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/embed");
            var client=new HttpEmbeddingClient(RestClient.builder(),new ObjectMapper(),properties,new SimpleMeterRegistry());

            assertThatThrownBy(()->client.embedDocuments(List.of("Wiki content")))
                    .isInstanceOfSatisfying(EmbeddingProviderException.class,failure->{
                        assertThat(failure.statusCode()).isEqualTo(400);
                        assertThat(failure.providerCode()).isEqualTo("provider_quota_exceeded");
                        assertThat(failure.isQuotaExhausted()).isTrue();
                    });
        }finally{server.stop(0);}
    }
}
