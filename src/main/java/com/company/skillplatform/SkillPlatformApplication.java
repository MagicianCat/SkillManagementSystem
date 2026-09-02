package com.company.skillplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Skill 管理平台启动入口。 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class SkillPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillPlatformApplication.class, args);
    }
}
