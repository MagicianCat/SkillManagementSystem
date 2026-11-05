package com.company.skillplatform.agent.infrastructure;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentExecutionConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor agentRuntimeExecutor(
            @Value("${agent.runtime.max-concurrent-runs:8}") int maxConcurrent,
            @Value("${agent.runtime.max-queued-runs:32}") int maxQueued) {
        if (maxConcurrent < 1 || maxQueued < 1) throw new IllegalArgumentException("Agent executor capacity must be positive");
        return new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxQueued), runnable -> {
                    Thread thread = new Thread(runnable, "agent-runtime");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
