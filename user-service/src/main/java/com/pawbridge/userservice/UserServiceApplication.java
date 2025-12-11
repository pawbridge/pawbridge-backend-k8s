package com.pawbridge.userservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Spring Data JPA Auditing을 전역적으로 활성화
@EnableJpaAuditing
// Feign Client 활성화 (animal-service 호출용)
@EnableFeignClients
// 스케줄링 활성화 (Outbox Cleanup)
@EnableScheduling
public class UserServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
