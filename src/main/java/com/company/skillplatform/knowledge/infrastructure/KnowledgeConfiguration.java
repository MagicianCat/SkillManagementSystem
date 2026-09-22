package com.company.skillplatform.knowledge.infrastructure;

import com.company.skillplatform.knowledge.domain.MarkdownHeadingChunker;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfiguration {
    @Bean public MarkdownHeadingChunker markdownHeadingChunker(KnowledgeProperties properties) {
        return new MarkdownHeadingChunker(properties.getChunk().getTargetChars(), properties.getChunk().getOverlapChars());
    }
    @Bean(name = "knowledgeIndexExecutor") public Executor knowledgeIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(2); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("knowledge-index-"); executor.initialize(); return executor;
    }
}
