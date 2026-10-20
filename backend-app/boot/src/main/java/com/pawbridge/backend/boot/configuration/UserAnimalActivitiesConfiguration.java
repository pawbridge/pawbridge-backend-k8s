package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.animalactivity.infrastructure.*;
import com.pawbridge.backend.user.internal.animalactivity.web.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.user-animal-activities",name="enabled",havingValue="true",matchIfMissing=false)
public class UserAnimalActivitiesConfiguration {
    @Bean(destroyMethod="close") HttpClient userAnimalHttpClient(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();}
    @Bean UserAnimalActivities.AnimalLookup userAnimalLookup(@Qualifier("userAnimalHttpClient") HttpClient client,@Value("${service.animal.url:}") String address){
        URI uri=URI.create(address);if(!uri.isAbsolute()||!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||(!uri.getPath().isEmpty()&&!uri.getPath().equals("/")))throw new IllegalArgumentException("An explicit Animal HTTP origin is required");
        var requests=new JdkClientHttpRequestFactory(client);requests.setReadTimeout(Duration.ofSeconds(60));return new HttpUserAnimalLookup(RestClient.builder().baseUrl(uri.toString()).requestFactory(requests).build());
    }
    @Bean UserAnimalActivities userAnimalActivities(@Qualifier("writeJdbc") JdbcTemplate jdbc,@Qualifier("writeTransactionManager") PlatformTransactionManager manager,UserAnimalActivities.AnimalLookup animals){
        var write=new TransactionTemplate(manager);var read=new TransactionTemplate(manager);read.setReadOnly(true);return new JdbcUserAnimalActivities(jdbc,write,read,animals);
    }
    @Bean UserAnimalActivitiesController userAnimalActivitiesController(UserAnimalActivities activities){return new UserAnimalActivitiesController(activities);}
    @Bean UserAnimalActivitiesExceptionHandler userAnimalActivitiesExceptionHandler(){return new UserAnimalActivitiesExceptionHandler();}
}
