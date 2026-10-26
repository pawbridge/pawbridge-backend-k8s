package com.pawbridge.backend.animal.internal.sheltercommand.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import com.pawbridge.backend.animal.api.AnimalShelterMembers
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandConflictException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandForbiddenException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityNotFoundException
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

/** Original CUD on the existing Animal writer; User lock always precedes Shelter lock. */
class JpaShelterCommands(
    private val entities: EntityManager,
    manager: JpaTransactionManager?,
    private val writes: TransactionOperations,
    private val search: SearchDocumentWriter,
    private val actors: AnimalCommandActors,
    private val members: AnimalShelterMembers,
) : ShelterCommands {
    private val mapper = ShelterMapper()

    init {
        if (manager == null ||
            manager.dataSource == null ||
            manager.dataSource !== search.dataSource() ||
            writes !is TransactionTemplate ||
            writes.transactionManager !== manager ||
            writes.isReadOnly ||
            writes.propagationBehavior != TransactionDefinition.PROPAGATION_REQUIRED ||
            writes.isolationLevel != TransactionDefinition.ISOLATION_READ_COMMITTED
        ) {
            throw IllegalArgumentException("Shelter commands require the same READ_COMMITTED writer transaction")
        }
    }

    override fun create(verifiedUserId: Long?, request: ShelterCommands.Create): ShelterCommands.Details {
        return writes.execute { _ ->
            requireAdministrator(currentActor(verifiedUserId))
            requireRollbackCharset(
                request.careRegNo,
                request.name,
                request.phone,
                request.address,
                request.ownerName,
                request.organizationName,
                request.email,
                request.introduction,
                request.adoptionProcedure,
                request.operatingHours,
            )
            val shelter = mapper.toEntity(request)
            entities.persist(shelter)
            entities.flush()
            search.shelter(shelter.id!!)
            mapper.toDetailResponse(shelter)
        }!!
    }

    override fun update(verifiedUserId: Long?, id: Long?, request: ShelterCommands.Update): ShelterCommands.Details {
        return writes.execute { _ ->
            val actor = currentActor(verifiedUserId)
            val shelter = lockedShelter(id)
            if ("ROLE_ADMIN" != actor.role) {
                val actorCareRegNo = actor.careRegNo
                if (actorCareRegNo == null || isBlank(actorCareRegNo) || actorCareRegNo != shelter.careRegNo) {
                    throw ShelterCommandForbiddenException()
                }
            }
            requireRollbackCharset(
                request.phone,
                request.email,
                request.introduction,
                request.adoptionProcedure,
                request.operatingHours,
            )
            shelter.updateInfo(
                request.phone,
                request.email,
                request.introduction,
                request.adoptionProcedure,
                request.operatingHours,
            )
            entities.flush()
            mapper.toDetailResponse(shelter)
        }!!
    }

    override fun delete(verifiedUserId: Long?, id: Long?) {
        writes.executeWithoutResult { _ ->
            requireAdministrator(currentActor(verifiedUserId))
            val shelter = lockedShelter(id)
            if (existsByShelterId(id) || members.exists(shelter.careRegNo)) {
                throw ShelterCommandConflictException()
            }
            entities.remove(shelter)
        }
    }

    private fun currentActor(verifiedUserId: Long?): Actor {
        if (verifiedUserId == null || verifiedUserId <= 0) {
            throw ShelterCommandForbiddenException()
        }
        val actor: Actor? = actors.find(verifiedUserId)
        if (actor == null ||
            actor.userId != verifiedUserId ||
            !actor.active ||
            actor.deleted ||
            !("ROLE_ADMIN" == actor.role || "ROLE_SHELTER" == actor.role)
        ) {
            throw ShelterCommandForbiddenException()
        }
        return actor
    }

    private fun requireAdministrator(actor: Actor) {
        if ("ROLE_ADMIN" != actor.role) {
            throw ShelterCommandForbiddenException()
        }
    }

    private fun lockedShelter(id: Long?): Shelter {
        // Hibernate may render PESSIMISTIC_WRITE as FOR NO KEY UPDATE, compatible with the
        // membership approval's FK-style KEY SHARE. This explicit lock must exclude it.
        val lockedRows = entities.createNativeQuery("SELECT id FROM pawbridge_animal.shelters WHERE id = :id FOR UPDATE")
            .setParameter("id", id)
            .resultList
        if (lockedRows.isEmpty()) {
            throw EntityNotFoundException("Shelter not found: $id")
        }
        return entities.find(Shelter::class.java, id) ?: throw EntityNotFoundException("Shelter not found: $id")
    }

    private fun existsByShelterId(id: Long?): Boolean {
        return !entities.createQuery("select a.id from Animal a where a.shelter.id = :shelterId", Long::class.javaObjectType)
            .setParameter("shelterId", id)
            .setMaxResults(1)
            .resultList
            .isEmpty()
    }

    private companion object {
        // Java String.isBlank() uses Character.isWhitespace; Kotlin isBlank() also accepts NBSP.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        // Mirror the existing PostgreSQL rollback-compatibility CHECK before it can log a failed row.
        private fun requireRollbackCharset(vararg values: String?) {
            for (value in values) {
                if (value != null && value.codePoints().anyMatch { code -> code > Char.MAX_VALUE.code }) {
                    throw IllegalArgumentException(PostgresqlRollbackCharsetViolation.MESSAGE)
                }
            }
        }
    }
}
