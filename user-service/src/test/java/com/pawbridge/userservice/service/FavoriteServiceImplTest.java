package com.pawbridge.userservice.service;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.response.FavoriteListResponseDto;
import com.pawbridge.userservice.entity.Favorite;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.repository.FavoriteRepository;
import com.pawbridge.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FavoriteServiceImplTest {

    private final FavoriteRepository favorites = mock(FavoriteRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AnimalServiceClient animals = mock(AnimalServiceClient.class);
    private final FavoriteServiceImpl service = new FavoriteServiceImpl(favorites, users, mock(OutboxService.class), animals);

    @Test
    void propagatesAnimalLookupFailureInsteadOfMarkingFavoritesDeleted() {
        givenFavorite();
        IllegalStateException failure = new IllegalStateException("animal-service unavailable");
        when(animals.getAnimalsByIds(List.of(3L))).thenThrow(failure);

        assertThatThrownBy(() -> service.getFavorites(1L)).isSameAs(failure);
    }

    @Test
    void marksFavoriteDeletedOnlyWhenAnimalIsAbsentFromSuccessfulLookup() {
        givenFavorite();
        when(animals.getAnimalsByIds(List.of(3L))).thenReturn(List.of());

        FavoriteListResponseDto result = service.getFavorites(1L);

        assertThat(result.favorites()).hasSize(1);
        assertThat(result.favorites().get(0).status()).isEqualTo("DELETED");
    }

    private void givenFavorite() {
        User user = User.builder().userId(1L).build();
        Favorite favorite = Favorite.builder().favoriteId(2L).user(user).animalId(3L).build();
        when(users.existsById(1L)).thenReturn(true);
        when(favorites.findAllByUserUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(favorite));
    }
}
