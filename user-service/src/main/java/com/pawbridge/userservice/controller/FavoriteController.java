package com.pawbridge.userservice.controller;

import com.pawbridge.userservice.dto.response.FavoriteListResponseDto;
import com.pawbridge.userservice.dto.response.FavoriteResponseDto;
import com.pawbridge.userservice.service.FavoriteService;
import com.pawbridge.userservice.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 동물 좋아요 컨트롤러 (CUD 전용)
 * - 좋아요 추가/제거/확인
 * - 조회는 MyPageController에서 담당
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * 동물 좋아요 추가
     */
    @PostMapping("/{animalId}")
    public ResponseEntity<ResponseDTO<FavoriteResponseDto>> addFavorite(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long animalId) {

        FavoriteResponseDto favoriteResponseDto = favoriteService.addFavorite(userId, animalId);
        ResponseDTO<FavoriteResponseDto> response = ResponseDTO.okWithData(favoriteResponseDto, "좋아요가 추가되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 동물 좋아요 제거
     */
    @DeleteMapping("/{animalId}")
    public ResponseEntity<ResponseDTO<Void>> removeFavorite(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long animalId) {

        favoriteService.removeFavorite(userId, animalId);
        ResponseDTO<Void> response = ResponseDTO.okWithMessage("좋아요가 제거되었습니다.");
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 좋아요 여부 확인
     */
    @GetMapping("/{animalId}/check")
    public ResponseEntity<ResponseDTO<Boolean>> isFavorite(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PathVariable Long animalId) {

        boolean isFavorite = favoriteService.isFavorite(userId, animalId);
        ResponseDTO<Boolean> response = ResponseDTO.okWithData(isFavorite);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
