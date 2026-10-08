package com.company.skillplatform.codegraph.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties({CodeGraphWorkerProperties.class, CodeGraphWorkflowProperties.class,
        CodeGraphSemanticProperties.class})
public class CodeGraphConfiguration {
    @Bean(name = "codeGraphSemanticExecutor")
    public Executor codeGraphSemanticExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(2); executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("code-graph-semantic-"); executor.initialize(); return executor;
    }
}
