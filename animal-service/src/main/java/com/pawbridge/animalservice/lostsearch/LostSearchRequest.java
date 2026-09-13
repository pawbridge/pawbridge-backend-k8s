package com.pawbridge.animalservice.lostsearch;

import com.pawbridge.animalservice.enums.Species;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

@Data
public class LostSearchRequest {
    @NotNull
    private MultipartFile image;
    @NotNull
    private Species species;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate lostDate;
    @Size(max = 100)
    private String region;
    @Size(max = 500)
    private String description;
}
