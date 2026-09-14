package com.pawbridge.animalservice.lostsearch;

import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class LostSearchController {
    private final LostSearchService service;

    @PostMapping(value = "/api/v1/animals/lost-candidates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LostSearchResponse search(@Valid @ModelAttribute LostSearchRequest request) {
        return service.search(request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> searchError(ResponseStatusException ex) {
        var response = ResponseEntity.status(ex.getStatusCode());
        if (ex.getStatusCode().value() == 503) response.header("Retry-After", "3");
        return response.body(Map.of("message", ex.getReason()));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<Map<String, String>> invalidInput() {
        return ResponseEntity.badRequest().body(Map.of("message", "사진·종류·날짜·지역·특징 입력을 확인해 주세요"));
    }
}
