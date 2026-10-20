package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps the approved shelter present until the caller commits the User association. */
public final class MembershipLockedAnimalShelterReads implements AnimalShelterReads {
    private static final String LOCK = "SELECT id,care_reg_no,name FROM pawbridge_animal.shelters WHERE care_reg_no=? FOR KEY SHARE";
    private final AnimalShelterReads delegate;
    private final JdbcTemplate jdbc;

    public MembershipLockedAnimalShelterReads(AnimalShelterReads delegate, JdbcTemplate jdbc) {
        this.delegate = Objects.requireNonNull(delegate);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Shelter findByCareRegNo(String careRegNo) {
        DataSource source = jdbc.getDataSource();
        requireWriter(source);
        Object binding = TransactionSynchronizationManager.getResource(source);
        // Preserve the existing independent full-detail/public-information lookup first.
        Shelter found = delegate.findByCareRegNo(careRegNo);
        requireWriter(source);
        if (jdbc.getDataSource() != source
                || TransactionSynchronizationManager.getResource(source) != binding) throw unavailable();
        if (found == null) return null;
        List<Shelter> locked;
        try {
            locked = jdbc.query(LOCK, (row, index) -> new Shelter(row.getObject("id", Long.class),
                    row.getString("care_reg_no"), row.getString("name")), careRegNo);
        } catch (RuntimeException failure) {
            throw new AnimalShelterReadUnavailableException(failure);
        }
        if (locked.size() != 1 || !Objects.equals(found.id(), locked.getFirst().id())
                || !Objects.equals(found.careRegNo(), locked.getFirst().careRegNo())
                || !Objects.equals(careRegNo, locked.getFirst().careRegNo())) throw unavailable();
        return found;
    }

    private static void requireWriter(DataSource source) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || source == null || !TransactionSynchronizationManager.hasResource(source)) throw unavailable();
    }

    private static AnimalShelterReadUnavailableException unavailable() {
        return new AnimalShelterReadUnavailableException(
                new IllegalStateException("Shelter membership locking is unavailable"));
    }
}
