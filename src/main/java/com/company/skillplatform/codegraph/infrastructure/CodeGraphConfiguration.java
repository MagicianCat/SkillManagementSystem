package com.company.skillplatform.codegraph.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CodeGraphWorkerProperties.class)
public class CodeGraphConfiguration {}
