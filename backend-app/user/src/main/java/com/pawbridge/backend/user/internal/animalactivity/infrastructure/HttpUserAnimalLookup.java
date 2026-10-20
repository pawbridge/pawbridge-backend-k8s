package com.pawbridge.backend.user.internal.animalactivity.infrastructure;

import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

public final class HttpUserAnimalLookup implements UserAnimalActivities.AnimalLookup {
    private final RestClient client;
    public HttpUserAnimalLookup(RestClient client){this.client=client;}
    public List<UserAnimalActivities.Animal> batch(List<Long> ids){return client.post().uri("/api/v1/mypage/animals/batch").body(ids).retrieve().body(new ParameterizedTypeReference<List<UserAnimalActivities.Animal>>(){});}
    public UserAnimalActivities.Shelter shelter(String careRegNo){return client.get().uri(builder->builder.pathSegment("api","v1","shelters","by-care-reg-no",careRegNo).build()).retrieve().body(UserAnimalActivities.Shelter.class);}
    public UserAnimalActivities.AnimalPage byShelter(Long shelterId,UserAnimalActivities.PageRequest request){return client.get().uri(builder->builder.pathSegment("api","v1","mypage","animals","by-shelter",shelterId.toString())
            .queryParam("page",request.page()).queryParam("size",request.size()).queryParam("sort",request.sort()).build()).retrieve().body(UserAnimalActivities.AnimalPage.class);}
}
