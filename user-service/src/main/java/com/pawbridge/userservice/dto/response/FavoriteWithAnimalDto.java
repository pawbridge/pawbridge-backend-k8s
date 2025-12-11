package com.pawbridge.userservice.dto.response;

import com.pawbridge.userservice.entity.Favorite;

import java.time.LocalDateTime;

/**
 * 찜 + 동물 정보 통합 DTO
 */
public record FavoriteWithAnimalDto(
        // Favorite 정보
        Long favoriteId,
        Long userId,
        Long animalId,
        LocalDateTime createdAt,

        // Animal 정보
        String breed,
        String species,
        String gender,
        Integer age,
        String imageUrl,
        String shelterName,
        String status
) {
    /**
     * Favorite + AnimalResponse 조합
     */
    public static FavoriteWithAnimalDto of(Favorite favorite, AnimalResponse animal) {
        return new FavoriteWithAnimalDto(
                favorite.getFavoriteId(),
                favorite.getUser().getUserId(),
                favorite.getAnimalId(),
                favorite.getCreatedAt(),
                animal.getBreed(),
                animal.getSpecies(),
                animal.getGender(),
                animal.getAge(),
                animal.getImageUrl(),
                animal.getShelterName(),
                animal.getStatus()
        );
    }

    /**
     * 동물 정보 없이 생성 (동물이 삭제된 경우)
     */
    public static FavoriteWithAnimalDto ofWithoutAnimal(Favorite favorite) {
        return new FavoriteWithAnimalDto(
                favorite.getFavoriteId(),
                favorite.getUser().getUserId(),
                favorite.getAnimalId(),
                favorite.getCreatedAt(),
                null,  // breed
                null,  // species
                null,  // gender
                null,  // age
                null,  // imageUrl
                null,  // shelterName
                "DELETED"  // status
        );
    }
}
