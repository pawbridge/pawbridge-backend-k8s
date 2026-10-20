package com.pawbridge.backend.animal.internal.recommendation.infrastructure;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** No enclosing transaction: the source read ends before Python, then candidates are read once. */
public final class JdbcAnimalRecommendations implements AnimalRecommendations {
    private static final String CANDIDATES = "SELECT a.id,a.apms_notice_no,a.species,a.breed,a.gender,"
            + "a.birth_year,a.weight,a.color,a.happen_date,a.special_mark,a.status,a.notice_end_date,"
            + "a.image_url,a.favorite_count,a.created_at,s.id AS shelter_id,s.name AS shelter_name "
            + "FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s ON s.id=a.shelter_id "
            + "WHERE a.id IN (:ids)";
    private final AnimalDiscovery animals;
    private final PythonAnimalRecommendations python;
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations reads;

    public JdbcAnimalRecommendations(AnimalDiscovery animals, PythonAnimalRecommendations python,
                                     DataSource source, PlatformTransactionManager manager) {
        this.animals = Objects.requireNonNull(animals);
        this.python = Objects.requireNonNull(python);
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(5);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
        this.reads = transaction;
    }

    JdbcAnimalRecommendations(AnimalDiscovery animals, PythonAnimalRecommendations python,
                              NamedParameterJdbcOperations jdbc, TransactionOperations reads) {
        this.animals = animals;
        this.python = python;
        this.jdbc = jdbc;
        this.reads = reads;
    }

    @Override
    public List<Card> recommend(long animalId) {
        var source = animals.findById(animalId);
        if (source.species() != Species.DOG && source.species() != Species.CAT) return List.of();
        List<Long> ids;
        try {
            ids = python.recommend(animalId, source.species().name());
        } catch (Exception failure) {
            throw new RecommendationUnavailableException();
        }
        if (ids == null || ids.size() > 6 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new RecommendationUnavailableException();
        }
        List<Long> candidates = ids.stream().filter(id -> id != animalId).distinct().limit(6).toList();
        if (candidates.isEmpty()) return List.of();
        Map<Long, Card> current = reads.execute(status -> jdbc.query(CANDIDATES,
                        new MapSqlParameterSource("ids", candidates), JdbcAnimalRecommendations::card).stream()
                .collect(Collectors.toMap(Card::id, Function.identity())));
        return candidates.stream().map(current::get).filter(Objects::nonNull)
                .filter(card -> card.species() == source.species())
                .filter(card -> card.status() == AnimalStatus.NOTICE || card.status() == AnimalStatus.PROTECT)
                .toList();
    }

    private static Card card(ResultSet row, int number) throws SQLException {
        Integer birthYear = row.getObject("birth_year", Integer.class);
        return new Card(row.getObject("id", Long.class), row.getString("apms_notice_no"),
                Species.valueOf(row.getString("species")), row.getString("breed"),
                Gender.valueOf(row.getString("gender")), birthYear,
                birthYear == null ? null : LocalDate.now().getYear() - birthYear,
                row.getString("weight"), row.getString("color"), row.getObject("happen_date", LocalDate.class),
                row.getString("special_mark"), AnimalStatus.valueOf(row.getString("status")),
                row.getObject("notice_end_date", LocalDate.class), row.getString("image_url"),
                row.getObject("favorite_count", Integer.class), row.getObject("shelter_id", Long.class),
                row.getString("shelter_name"), row.getObject("created_at", LocalDateTime.class));
    }
}
