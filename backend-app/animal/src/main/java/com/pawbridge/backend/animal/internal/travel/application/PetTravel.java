package com.pawbridge.backend.animal.internal.travel.application;

/** The three existing public catalog reads; collection and external HTTP are outside this contract. */
public interface PetTravel {
    PetTravelResponse.Regions regions();
    PetTravelResponse.Places places(String areaCode, int page);
    PetTravelResponse.Detail detail(String contentId);
}
