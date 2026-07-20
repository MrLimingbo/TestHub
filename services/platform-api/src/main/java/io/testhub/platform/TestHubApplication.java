package io.testhub.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TestHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestHubApplication.class, args);
    }
}
