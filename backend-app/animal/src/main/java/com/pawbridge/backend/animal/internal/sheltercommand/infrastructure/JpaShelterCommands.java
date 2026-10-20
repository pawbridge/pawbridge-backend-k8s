package com.pawbridge.backend.animal.internal.sheltercommand.infrastructure;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor;
import com.pawbridge.backend.animal.api.AnimalShelterMembers;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.sheltercommand.application.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.util.Objects;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Original CUD on the existing Animal writer; User lock always precedes Shelter lock. */
public final class JpaShelterCommands implements ShelterCommands {
    private final EntityManager entities;
    private final TransactionOperations writes;
    private final SearchDocumentWriter search;
    private final AnimalCommandActors actors;
    private final AnimalShelterMembers members;
    private final ShelterMapper mapper = new ShelterMapper();

    public JpaShelterCommands(EntityManager entities, JpaTransactionManager manager, TransactionOperations writes,
            SearchDocumentWriter search, AnimalCommandActors actors, AnimalShelterMembers members) {
        this.entities = Objects.requireNonNull(entities);
        this.writes = Objects.requireNonNull(writes);
        this.search = Objects.requireNonNull(search);
        this.actors = Objects.requireNonNull(actors);
        this.members = Objects.requireNonNull(members);
        if (manager == null || manager.getDataSource() == null || manager.getDataSource() != search.dataSource()
                || !(writes instanceof TransactionTemplate template) || template.getTransactionManager() != manager
                || template.isReadOnly() || template.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || template.getIsolationLevel() != TransactionDefinition.ISOLATION_READ_COMMITTED)
            throw new IllegalArgumentException("Shelter commands require the same READ_COMMITTED writer transaction");
    }

    @Override public Details create(Long verifiedUserId, Create request) {
        return writes.execute(status -> {
            requireAdministrator(currentActor(verifiedUserId));
            requireRollbackCharset(request.careRegNo(),request.name(),request.phone(),request.address(),request.ownerName(),
                    request.organizationName(),request.email(),request.introduction(),request.adoptionProcedure(),request.operatingHours());
            Shelter shelter = mapper.toEntity(request);
            entities.persist(shelter);
            entities.flush();
            search.shelter(shelter.getId());
            return mapper.toDetailResponse(shelter);
        });
    }

    @Override public Details update(Long verifiedUserId, Long id, Update request) {
        return writes.execute(status -> {
            Actor actor = currentActor(verifiedUserId);
            Shelter shelter = lockedShelter(id);
            if (!"ROLE_ADMIN".equals(actor.role()) && (actor.careRegNo() == null || actor.careRegNo().isBlank()
                    || !actor.careRegNo().equals(shelter.getCareRegNo()))) throw new ShelterCommandForbiddenException();
            requireRollbackCharset(request.phone(),request.email(),request.introduction(),request.adoptionProcedure(),request.operatingHours());
            shelter.updateInfo(request.phone(), request.email(), request.introduction(), request.adoptionProcedure(), request.operatingHours());
            return mapper.toDetailResponse(shelter);
        });
    }

    @Override public void delete(Long verifiedUserId, Long id) {
        writes.executeWithoutResult(status -> {
            requireAdministrator(currentActor(verifiedUserId));
            Shelter shelter = lockedShelter(id);
            if (existsByShelterId(id) || members.exists(shelter.getCareRegNo())) throw new ShelterCommandConflictException();
            entities.remove(shelter);
        });
    }

    private Actor currentActor(Long verifiedUserId) {
        if (verifiedUserId == null || verifiedUserId <= 0) throw new ShelterCommandForbiddenException();
        Actor actor = actors.find(verifiedUserId);
        if (actor == null || actor.userId() != verifiedUserId || !actor.active() || actor.deleted()
                || !("ROLE_ADMIN".equals(actor.role()) || "ROLE_SHELTER".equals(actor.role())))
            throw new ShelterCommandForbiddenException();
        return actor;
    }
    private void requireAdministrator(Actor actor) {
        if (!"ROLE_ADMIN".equals(actor.role())) throw new ShelterCommandForbiddenException();
    }
    private Shelter lockedShelter(Long id) {
        // Hibernate may render PESSIMISTIC_WRITE as FOR NO KEY UPDATE, compatible with the
        // membership approval's FK-style KEY SHARE. This explicit lock must exclude it.
        if (entities.createNativeQuery("SELECT id FROM pawbridge_animal.shelters WHERE id = :id FOR UPDATE")
                .setParameter("id", id).getResultList().isEmpty())
            throw new EntityNotFoundException("Shelter not found: " + id);
        Shelter shelter = entities.find(Shelter.class, id);
        if (shelter == null) throw new EntityNotFoundException("Shelter not found: " + id);
        return shelter;
    }
    private boolean existsByShelterId(Long id) {
        return !entities.createQuery("select a.id from Animal a where a.shelter.id = :shelterId", Long.class)
                .setParameter("shelterId", id).setMaxResults(1).getResultList().isEmpty();
    }
    // Mirror the existing PostgreSQL rollback-compatibility CHECK before it can log a failed row.
    private static void requireRollbackCharset(String... values) {
        for(String value:values) {
            if(value!=null && value.codePoints().anyMatch(code->code>Character.MAX_VALUE))
                throw new IllegalArgumentException(
                        com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation.MESSAGE);
        }
    }

}
