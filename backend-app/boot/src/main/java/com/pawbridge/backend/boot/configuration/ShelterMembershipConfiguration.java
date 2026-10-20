package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.shelter.infrastructure.*;
import com.pawbridge.backend.user.internal.shelter.web.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.shelter-membership",name="enabled",havingValue="true",matchIfMissing=false)
@Import(ShelterMembershipModuleConfiguration.class)
public class ShelterMembershipConfiguration {
    public ShelterMembershipConfiguration(@Value("${pawbridge.shelter-membership.animal-backend:http}") String backend) {
        if (!backend.equals("http") && !backend.equals("module")) throw new IllegalArgumentException("Unknown User Animal backend");
    }
    @Bean(destroyMethod="close") @ConditionalOnProperty(prefix="pawbridge.shelter-membership",name="animal-backend",havingValue="http",matchIfMissing=true)
    HttpClient shelterAnimalHttpClient(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();}
    @Bean @ConditionalOnProperty(prefix="pawbridge.shelter-membership",name="animal-backend",havingValue="http",matchIfMissing=true)
    ShelterMembership.ShelterLookup shelterAnimalLookup(@Qualifier("shelterAnimalHttpClient") HttpClient shelterAnimalHttpClient,
            @Value("${pawbridge.shelter-membership.animal-base-url:}") String address){
        URI uri=URI.create(address);
        if(!uri.isAbsolute()||!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||(!uri.getPath().isEmpty()&&!uri.getPath().equals("/")))
            throw new IllegalArgumentException("An explicit Animal HTTP origin is required");
        var factory=new JdkClientHttpRequestFactory(shelterAnimalHttpClient);factory.setReadTimeout(Duration.ofSeconds(60));
        return new HttpShelterLookup(RestClient.builder().requestFactory(factory).baseUrl(uri.toString()).build());
    }
    @Bean ShelterMembership shelterMembership(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") PlatformTransactionManager manager,
            ShelterMembership.ShelterLookup shelters,@Qualifier("readClock") Clock clock){
        var write=new TransactionTemplate(manager);var read=new TransactionTemplate(manager);read.setReadOnly(true);
        var statistics=new TransactionTemplate(manager);statistics.setReadOnly(true);statistics.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new JdbcShelterMembership(jdbc,write,read,statistics,shelters,clock);
    }
    @Bean ShelterApplicationController shelterApplicationController(ShelterMembership memberships){return new ShelterApplicationController(memberships);}
    @Bean AdminShelterApplicationController adminShelterApplicationController(ShelterMembership memberships){return new AdminShelterApplicationController(memberships);}
    @Bean AdminShelterMemberController adminShelterMemberController(ShelterMembership memberships){return new AdminShelterMemberController(memberships);}
    @Bean ShelterMembershipExceptionHandler shelterMembershipExceptionHandler(){return new ShelterMembershipExceptionHandler();}
}

// Top-level and imported only through the enabled feature; no nested auto-registration.
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.shelter-membership",name="animal-backend",havingValue="module")
@Import(AnimalModuleReadConfiguration.class)
class ShelterMembershipModuleConfiguration {
    @Bean ShelterMembership.ShelterLookup moduleShelterLookup(
            @Qualifier("animalModuleReads") com.pawbridge.backend.animal.api.AnimalShelterReads animals) {
        return new ModuleShelterLookup(animals);
    }
}
