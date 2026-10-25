package com.pawbridge.backend.animal.internal.travel.application

/** The three existing public catalog reads; collection and external HTTP are outside this contract. */
interface PetTravel {
    fun regions(): PetTravelResponse.Regions

    fun places(areaCode: String?, page: Int): PetTravelResponse.Places

    fun detail(contentId: String?): PetTravelResponse.Detail
}
