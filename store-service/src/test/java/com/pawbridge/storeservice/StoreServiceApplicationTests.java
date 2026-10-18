package com.pawbridge.storeservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.cloud.aws.credentials.access-key=test-access-key",
		"spring.cloud.aws.credentials.secret-key=test-secret-key",
		"spring.cloud.aws.region.static=auto",
		"spring.cloud.aws.s3.endpoint=http://localhost:9000",
		"spring.cloud.aws.s3.bucket=test-bucket",
		"pawbridge.storage.public-base-url=http://localhost:9000/test-bucket"
})
class StoreServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
