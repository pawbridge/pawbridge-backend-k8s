package com.pawbridge.backend.user.internal.shelter.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpShelterLookupTest {
    @Test void given__the_original_raw_animal_endpoint__when__looking_up__then__encode_the_registration_and_ignore_extra_dto_fields_without_auth_invention()throws Exception{
        var path=new AtomicReference<String>();var authorization=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{path.set(exchange.getRequestURI().getRawPath());authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));byte[] body="{\"id\":9,\"careRegNo\":\"등록 번호\",\"name\":\"보호소\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{var client=new HttpShelterLookup(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            assertEquals(new ShelterMembership.Shelter(9L,"등록 번호"),client.find("등록 번호"));assertTrue(path.get().startsWith("/api/v1/shelters/by-care-reg-no/"));assertTrue(path.get().contains("%"));assertNull(authorization.get());}
        finally{server.stop(0);}
    }
    @Test void given__animal_404__when__looking_up__then__raise_only_the_missing_shelter_marker()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{exchange.sendResponseHeaders(404,-1);exchange.close();});server.start();
        try{var client=new HttpShelterLookup(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());assertThrows(ShelterMembership.MissingShelter.class,()->client.find("reg"));}finally{server.stop(0);}
    }
}
