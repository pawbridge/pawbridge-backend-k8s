package com.pawbridge.backend.user.internal.animalactivity.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface UserAnimalActivities {
    record Favorite(Long favoriteId,Long userId,Long animalId,LocalDateTime createdAt) { }
    record Animal(Long id,String apmsNoticeNo,String species,String breed,String gender,Integer birthYear,Integer age,
            String specialMark,String status,LocalDate noticeEndDate,String imageUrl,Integer favoriteCount,
            Long shelterId,String shelterName,LocalDateTime createdAt) { }
    record FavoriteAnimal(Long favoriteId,Long userId,Long animalId,LocalDateTime createdAt,String breed,String species,
            String gender,Integer age,String imageUrl,String shelterName,String status,String apmsNoticeNo,String specialMark) { }
    record Favorites(Long userId,int totalCount,List<FavoriteAnimal> favorites) { }
    record AnimalPage(List<Animal> content,int page,int size,long totalElements,int totalPages,boolean last) { }
    record PageRequest(int page,int size,String sort) { }
    record Shelter(Long id,String name) { }
    interface AnimalLookup {
        List<Animal> batch(List<Long> ids);
        Shelter shelter(String careRegNo);
        AnimalPage byShelter(Long shelterId,PageRequest request);
    }
    Favorite add(long currentUserId,long animalId);
    void remove(long currentUserId,long animalId);
    Favorites favorites(long currentUserId);
    AnimalPage registered(long currentUserId,PageRequest request);
}
