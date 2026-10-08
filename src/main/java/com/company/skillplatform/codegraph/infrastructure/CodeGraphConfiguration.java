package com.company.skillplatform.codegraph.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CodeGraphWorkerProperties.class, CodeGraphWorkflowProperties.class})
public class CodeGraphConfiguration {}
