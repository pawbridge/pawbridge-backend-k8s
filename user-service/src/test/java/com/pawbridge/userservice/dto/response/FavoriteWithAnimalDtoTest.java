package com.pawbridge.userservice.dto.response;

import com.pawbridge.userservice.entity.Favorite;
import com.pawbridge.userservice.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FavoriteWithAnimalDtoTest {

    @Test
    void includesNoticeNumberAndSpecialMarkWhenAnimalExists() {
        Favorite favorite = favorite();
        AnimalResponse animal = AnimalResponse.builder()
                .breed("믹스견")
                .apmsNoticeNo("울산-중구-2026-00313")
                .specialMark("착하고 순함")
                .build();

        FavoriteWithAnimalDto result = FavoriteWithAnimalDto.of(favorite, animal);

        assertThat(result.apmsNoticeNo()).isEqualTo("울산-중구-2026-00313");
        assertThat(result.specialMark()).isEqualTo("착하고 순함");
    }

    @Test
    void keepsMissingAnimalSummaryEmpty() {
        FavoriteWithAnimalDto result = FavoriteWithAnimalDto.ofWithoutAnimal(favorite());

        assertThat(result.status()).isEqualTo("DELETED");
        assertThat(result.apmsNoticeNo()).isNull();
        assertThat(result.specialMark()).isNull();
    }

    private Favorite favorite() {
        User user = User.builder().userId(1L).build();
        return Favorite.builder().favoriteId(2L).user(user).animalId(3L).build();
    }
}
