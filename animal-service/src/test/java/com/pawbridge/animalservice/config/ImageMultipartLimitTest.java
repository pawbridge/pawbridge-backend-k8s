package com.pawbridge.animalservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ImageMultipartLimitTest {

    @Test
    void givenFiveTenMegabyteImages_whenMultipartLimitsAreLoaded_thenRequestHasRoomForMultipartOverhead() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        DataSize fileLimit = DataSize.parse(properties.getProperty("spring.servlet.multipart.max-file-size"));
        DataSize requestLimit = DataSize.parse(properties.getProperty("spring.servlet.multipart.max-request-size"));

        assertThat(fileLimit.toBytes()).isEqualTo(DataSize.ofMegabytes(10).toBytes());
        assertThat(requestLimit.toBytes()).isGreaterThan(fileLimit.toBytes() * 5);
    }
}
