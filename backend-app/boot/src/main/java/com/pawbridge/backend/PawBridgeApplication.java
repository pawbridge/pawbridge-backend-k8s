package com.pawbridge.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PawBridgeApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PawBridgeApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args).close();
    }
}
