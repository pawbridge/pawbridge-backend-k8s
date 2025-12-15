package com.pawbridge.communityservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class CommunityServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommunityServiceApplication.class, args);
	}


}
