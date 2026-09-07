package com.company.skillplatform.agent.infrastructure;
import java.util.concurrent.*;
import org.springframework.context.annotation.*;
@Configuration public class AgentExecutionConfiguration{
 @Bean(destroyMethod="shutdown") ExecutorService agentRuntimeExecutor(){return Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"agent-runtime");t.setDaemon(true);return t;});}
}
