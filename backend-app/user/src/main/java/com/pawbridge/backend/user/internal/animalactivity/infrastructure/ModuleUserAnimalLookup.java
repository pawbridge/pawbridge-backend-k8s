package com.pawbridge.backend.user.internal.animalactivity.infrastructure;

import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import java.util.List;

/** Replaces all three activity HTTP calls only when Root explicitly selects the module contract. */
public final class ModuleUserAnimalLookup implements UserAnimalActivities.AnimalLookup {
    private final AnimalMemberReads animals;
    public ModuleUserAnimalLookup(AnimalMemberReads animals) { this.animals = animals; }
    @Override public List<UserAnimalActivities.Animal> batch(List<Long> ids) {
        var result = animals.batch(ids);
        return result == null ? null : result.stream().map(ModuleUserAnimalLookup::animal).toList();
    }
    @Override public UserAnimalActivities.Shelter shelter(String careRegNo) {
        var result = animals.findByCareRegNo(careRegNo);
        return result == null ? null : new UserAnimalActivities.Shelter(result.id(), result.name());
    }
    @Override public UserAnimalActivities.AnimalPage byShelter(Long shelterId, UserAnimalActivities.PageRequest request) {
        var page = animals.byShelter(shelterId, new AnimalMemberReads.PageRequest(request.page(), request.size(), request.sort()));
        if (page == null) return null;
        // Existing HTTP maps Spring Page.number to an absent User.page field, leaving page=0.
        return new UserAnimalActivities.AnimalPage(page.content() == null ? null : page.content().stream().map(ModuleUserAnimalLookup::animal).toList(),
                0, page.size(), page.totalElements(), page.totalPages(), page.last());
    }
    private static UserAnimalActivities.Animal animal(AnimalMemberReads.Animal row) {
        if (row == null) return null;
        return new UserAnimalActivities.Animal(row.id(), row.apmsNoticeNo(), row.species(), row.breed(), row.gender(),
                row.birthYear(), row.age(), row.specialMark(), row.status(), row.noticeEndDate(), row.imageUrl(), row.favoriteCount(),
                row.shelterId(), row.shelterName(), row.createdAt());
    }
}
