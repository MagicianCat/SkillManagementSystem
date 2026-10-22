package com.company.skillplatform.auth.infrastructure;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FeishuCardProperties.class)
public class FeishuCardConfiguration {
    @Bean
    SmartInitializingSingleton validateFeishuCardProperties(FeishuCardProperties card, FeishuProperties feishu) {
        return () -> card.validate(feishu.eventEnabled());
    }
}
