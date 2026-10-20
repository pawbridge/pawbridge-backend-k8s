package com.pawbridge.backend.user.internal.shelter.infrastructure;

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

public final class HttpShelterLookup implements ShelterMembership.ShelterLookup {
    private final RestClient client;
    public HttpShelterLookup(RestClient client){this.client=client;}
    public ShelterMembership.Shelter find(String careRegNo){
        try{return client.get().uri(builder->builder.pathSegment("api","v1","shelters","by-care-reg-no",careRegNo).build())
                .retrieve().body(ShelterMembership.Shelter.class);}
        catch(HttpClientErrorException.NotFound missing){throw new ShelterMembership.MissingShelter();}
    }
}
