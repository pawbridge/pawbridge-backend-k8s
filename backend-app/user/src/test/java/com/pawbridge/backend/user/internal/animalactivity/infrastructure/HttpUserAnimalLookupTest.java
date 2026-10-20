package com.pawbridge.backend.user.internal.animalactivity.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpUserAnimalLookupTest {
    @Test void given__the_three_exact_raw_animal_endpoints__when__looking_up__then__retain_batch_body_encoded_registration_sort_and_source_page_field_mismatch()throws Exception{
        var paths=new ArrayList<String>();var methods=new ArrayList<String>();var bodies=new ArrayList<String>();var auth=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{String path=exchange.getRequestURI().toASCIIString();paths.add(path);methods.add(exchange.getRequestMethod());bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String response=path.contains("/batch")?"[{\"id\":8,\"species\":\"DOG\",\"createdAt\":\"2026-10-20T01:02:03.123456\"}]":path.contains("/by-care-reg-no/")?"{\"id\":4,\"name\":\"shelter\",\"careRegNo\":\"등록 번호\"}":"{\"content\":[],\"number\":3,\"size\":20,\"totalElements\":41,\"totalPages\":3,\"last\":false,\"pageable\":{}}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{var lookup=new HttpUserAnimalLookup(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());
            var batch=lookup.batch(List.of(8L,9L));assertEquals(8L,batch.getFirst().id());assertEquals(123456000,batch.getFirst().createdAt().getNano());assertEquals(4L,lookup.shelter("등록 번호").id());
            var page=lookup.byShelter(4L,new UserAnimalActivities.PageRequest(3,20,"name,desc,createdAt,asc"));assertEquals(0,page.page());assertEquals(41,page.totalElements());assertFalse(page.last());
            assertEquals(List.of("POST","GET","GET"),methods);assertEquals("[8,9]",bodies.getFirst());assertEquals("/api/v1/mypage/animals/batch",paths.getFirst());assertTrue(paths.get(1).startsWith("/api/v1/shelters/by-care-reg-no/%"));assertTrue(paths.getLast().contains("/api/v1/mypage/animals/by-shelter/4?page=3&size=20&sort=name,desc,createdAt,asc"));assertTrue(auth.stream().allMatch(Objects::isNull));
        }finally{server.stop(0);}
    }
    @Test void given__an_animal_500__when__batch_lookup_fails__then__propagate_instead_of_returning_empty()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
        try{var lookup=new HttpUserAnimalLookup(RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build());assertThrows(org.springframework.web.client.HttpServerErrorException.class,()->lookup.batch(List.of(8L)));}finally{server.stop(0);}
    }
}
