package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.animal.internal.command.infrastructure.service.NoticeNumberGenerator
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandController
import com.pawbridge.backend.animal.internal.command.web.AnimalCommandExceptionHandler
import com.pawbridge.backend.animal.internal.image.infrastructure.S3AnimalImages
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.orm.jpa.JpaTransactionManager

/** One opt-in writer persistence unit; no entity/repository scan or additional pool. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.animal-commands", name = ["enabled"], havingValue = "true")
@Import(AnimalJpaWriterConfiguration::class)
class AnimalCommandConfiguration {
    @Bean
    fun animalCommandMapper(): AnimalMapper {
        return AnimalMapper()
    }

    @Bean
    fun animalCommandNoticeNumbers(animals: AnimalRepository): NoticeNumberGenerator {
        return NoticeNumberGenerator(animals)
    }

    @Bean
    fun animalCommandService(
        search: SearchDocumentWriter,
        animals: AnimalRepository,
        shelters: ShelterRepository,
        mapper: AnimalMapper,
        numbers: NoticeNumberGenerator,
        actors: AnimalCommandActors,
        @Qualifier("animalCommandJpaTransactionManager") manager: JpaTransactionManager,
        photos: ObjectProvider<S3AnimalImages>,
    ): AnimalCommandService {
        // Photo storage exists only with pawbridge.animal-images.enabled; without it commands accept no new photo.
        return AnimalCommandService(search, animals, shelters, mapper, numbers, actors, manager, photos.ifAvailable)
    }

    @Bean
    fun animalCommandController(animals: AnimalCommandService): AnimalCommandController {
        return AnimalCommandController(animals)
    }

    @Bean
    fun animalCommandExceptionHandler(): AnimalCommandExceptionHandler {
        return AnimalCommandExceptionHandler()
    }
}
