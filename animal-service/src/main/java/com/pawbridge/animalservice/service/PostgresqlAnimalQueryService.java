package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.exception.AnimalNotFoundException;
import com.pawbridge.animalservice.exception.SearchProjectionPendingException;
import com.pawbridge.animalservice.search.KoreanSearchAnalyzer;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in text reads. PostgreSQL text relevance is not an ES/BM25 score or an image similarity score. */
@Service
@ConditionalOnProperty(prefix = "pawbridge.animal-query", name = "backend", havingValue = "postgresql")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
public class PostgresqlAnimalQueryService implements AnimalQueryService {
    private static final String FROM = " FROM animals a JOIN shelters s ON s.id=a.shelter_id ";
    private static final String COLUMNS = "a.id,a.apms_notice_no,a.species,a.breed,a.gender,a.birth_year,"
            + "a.special_mark,a.status,a.notice_end_date,a.image_url,a.favorite_count,a.created_at,"
            + "s.id AS shelter_id,s.name AS shelter_name";
    private static final Map<String,Integer> TEXT_WEIGHTS = Map.of(
            "a.special_mark",9,"a.breed",8,"a.color",7,"a.description",7,
            "a.happen_place",4,"s.name",2,"s.address",1);
    private final NamedParameterJdbcTemplate jdbc;
    private final AnimalRepository animals;
    private final AnimalMapper mapper;
    private final KoreanSearchAnalyzer analyzer;

    public PostgresqlAnimalQueryService(DataSource source, AnimalRepository animals, AnimalMapper mapper) {
        this(source,animals,mapper,Optional.empty());
    }

    @Autowired
    public PostgresqlAnimalQueryService(DataSource source, AnimalRepository animals, AnimalMapper mapper,
                                        Optional<KoreanSearchAnalyzer> analyzer) {
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(5);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.animals = animals;
        this.mapper = mapper;
        this.analyzer = analyzer.orElse(null);
    }

    @Override
    public AnimalDetailResponse findByApmsDesertionNo(String number) {
        return mapper.toDetailResponse(animals.findWithShelterByApmsDesertionNo(number)
                .orElseThrow(AnimalNotFoundException::new));
    }

    @Override
    public Page<AnimalResponse> searchAnimals(AnimalSearchRequest request, Pageable pageable) {
        validatePage(pageable);
        String keyword = text(request.getKeyword());
        String breed = text(request.getBreed());
        if(keyword!=null) terms(keyword);
        if(breed!=null) terms(breed);
        String ordering = ordering(pageable, keyword != null || breed != null);
        Conditions conditions = new Conditions();
        String notice = request.getNoticeNo() == null || request.getNoticeNo().isBlank() ? null : request.getNoticeNo().trim();
        conditions.equal("a.apms_notice_no","notice",notice);
        conditions.equal("a.species","species",request.getSpecies());
        conditions.equal("a.gender","gender",request.getGender());
        conditions.equal("a.neuter_status","neuter",request.getNeuterStatus());
        conditions.equal("a.shelter_id","shelter",request.getShelterId());
        if (request.getStatus() != null) conditions.equal("a.status","status",request.getStatus());
        else if (notice == null) conditions.where.add("a.status IN ('NOTICE','PROTECT')");
        int year = LocalDate.now().getYear();
        if (request.getMinAge() != null) {
            conditions.where.add("a.birth_year<=:maxBirthYear");
            conditions.parameters.addValue("maxBirthYear",year-request.getMinAge());
        }
        if (request.getMaxAge() != null) {
            conditions.where.add("a.birth_year>=:minBirthYear");
            conditions.parameters.addValue("minBirthYear",year-request.getMaxAge());
        }
        address(conditions,"region",request.getRegion());
        address(conditions,"city",request.getCity());
        if (breed != null) addBreed(conditions,breed);
        if (analyzer!=null && keyword!=null) {
            conditions.from=FROM+" LEFT JOIN animal_search_documents ad ON ad.animal_id=a.id "
                    +"LEFT JOIN shelter_search_documents sd ON sd.shelter_id=s.id ";
            requirePrepared(conditions);
            addAnalyzedText(conditions,"keyword",keyword);
        } else {
            if (keyword != null) addText(conditions,"keyword",keyword,TEXT_WEIGHTS);
        }
        return page(conditions,pageable,ordering);
    }

    @Override
    public Page<AnimalResponse> findExpiringSoonAnimals(Pageable pageable) {
        validatePage(pageable);
        Conditions conditions = new Conditions();
        conditions.equal("a.status","status",AnimalStatus.PROTECT);
        conditions.where.add("a.notice_end_date BETWEEN :today AND :deadline");
        LocalDate today = LocalDate.now();
        conditions.parameters.addValue("today",today).addValue("deadline",today.plusDays(3));
        Pageable sorted = PageRequest.of(pageable.getPageNumber(),pageable.getPageSize(),Sort.by("noticeEndDate"));
        return page(conditions,sorted,"a.notice_end_date ASC NULLS LAST,a.id ASC");
    }

    @Override
    public Page<AnimalResponse> findByShelterId(Long id, Pageable pageable) { return shelter(id,null,null,pageable); }
    @Override
    public Page<AnimalResponse> findByShelterIdAndSpecies(Long id, Species species, Pageable pageable) {
        return shelter(id,species,null,pageable);
    }
    @Override
    public Page<AnimalResponse> findByShelterIdAndStatus(Long id, AnimalStatus status, Pageable pageable) {
        return shelter(id,null,status,pageable);
    }
    private Page<AnimalResponse> shelter(Long id, Species species, AnimalStatus status, Pageable pageable) {
        validatePage(pageable);
        Conditions conditions = new Conditions();
        conditions.equal("a.shelter_id","shelter",id);
        conditions.equal("a.species","species",species);
        conditions.equal("a.status","status",status);
        return page(conditions,pageable,ordering(pageable,false));
    }

    @Override public long countBySpecies(Species species) { return count("a.species=:value",species.name()); }
    @Override public long countByStatus(AnimalStatus status) { return count("a.status=:value",status.name()); }
    @Override public long countByShelterId(Long id) { return count("a.shelter_id=:value",id); }
    @Override public long countBySpeciesAndStatus(Species species, AnimalStatus status) {
        return jdbc.queryForObject("SELECT count(*) FROM animals a WHERE a.species=:species AND a.status=:status",
                Map.of("species",species.name(),"status",status.name()),Long.class);
    }
    private long count(String predicate, Object value) {
        return jdbc.queryForObject("SELECT count(*) FROM animals a WHERE " + predicate,Map.of("value",value),Long.class);
    }

    private Page<AnimalResponse> page(Conditions conditions, Pageable pageable, String ordering) {
        String where = " WHERE " + (conditions.where.isEmpty()?"TRUE":String.join(" AND ",conditions.where));
        long total = jdbc.queryForObject("SELECT count(*)"+conditions.from+where,conditions.parameters,Long.class);
        if (total == 0 || pageable.getOffset() >= total) return new PageImpl<>(List.of(),pageable,total);
        conditions.parameters.addValue("limit",pageable.getPageSize()).addValue("offset",pageable.getOffset());
        String score = conditions.scores.isEmpty()?"0":String.join(" + ",conditions.scores);
        List<AnimalResponse> content = jdbc.query("SELECT "+COLUMNS+",("+score+") AS relevance"+conditions.from+where
                +" ORDER BY "+ordering+" LIMIT :limit OFFSET :offset",conditions.parameters,this::response);
        return new PageImpl<>(content,pageable,total);
    }

    /** Never return a partial keyword result while an import or repair is incomplete. */
    private void requirePrepared(Conditions conditions) {
        String filters=conditions.where.isEmpty()?"TRUE":String.join(" AND ",conditions.where);
        conditions.parameters.addValue("analyzerVersion",KoreanSearchAnalyzer.VERSION);
        Boolean pending=jdbc.queryForObject("SELECT EXISTS(SELECT 1"+FROM
                +"LEFT JOIN animal_search_documents ad ON ad.animal_id=a.id WHERE ("+filters+")"
                +" AND (a.search_dirty OR ad.animal_id IS NULL OR ad.source_revision<>a.search_revision"
                +" OR ad.analyzer_version<>:analyzerVersion)) OR EXISTS(SELECT 1 FROM shelters s"
                +" LEFT JOIN shelter_search_documents sd ON sd.shelter_id=s.id"
                +" WHERE (s.search_dirty OR sd.shelter_id IS NULL OR sd.source_revision<>s.search_revision"
                +" OR sd.analyzer_version<>:analyzerVersion) AND EXISTS(SELECT 1 FROM animals a"
                +" WHERE a.shelter_id=s.id AND ("+filters+")))",conditions.parameters,Boolean.class);
        if (Boolean.TRUE.equals(pending)) throw new SearchProjectionPendingException();
    }

    private void addAnalyzedText(Conditions conditions,String name,String value) {
        terms(value); // Preserve the public input bound in the analyzed path too.
        try {
            KoreanSearchAnalyzer.Relation relation=analyzer.queryRelation(value);
            List<String> analyzed=relation==null?analyzer.tokens(value):relation.tokens();
            if(analyzed.isEmpty()) { conditions.where.add("FALSE");return; }
            String vector="(ad.tokens || sd.tokens)";
            String query="plainto_tsquery('simple',:"+name+"Tokens)";
            conditions.parameters.addValue(name+"Tokens",String.join(" ",analyzed));
            // Relation queries already narrow through their more selective relation index.
            if (relation==null) {
                // Each term may occur in the animal OR its shelter. Narrow through the
                // separate GIN indexes without losing matches split across those documents.
                int tokenIndex=0;
                for (String token:new java.util.LinkedHashSet<>(analyzed)) {
                    String parameter=name+"Term"+tokenIndex++;
                    conditions.parameters.addValue(parameter,token);
                    String termQuery="plainto_tsquery('simple',:"+parameter+")";
                    // A Nori token may expand into several PostgreSQL lexemes. Only a
                    // single lexeme is a safe cross-document prefilter; final matching stays unchanged.
                    conditions.where.add("(numnode("+termQuery+")<>1 OR a.id IN (SELECT d.animal_id FROM animal_search_documents d WHERE d.tokens @@ "+termQuery
                            +" UNION ALL SELECT resident.id FROM shelter_search_documents shelter_doc"
                            +" JOIN animals resident ON resident.shelter_id=shelter_doc.shelter_id WHERE shelter_doc.tokens @@ "+termQuery+"))");
                    if (tokenIndex==3) break; // Bound planner work for heavily decomposed input.
                }
            }
            String match=vector+" @@ "+query;
            conditions.where.add(match);
            conditions.scores.add("ts_rank_cd("+vector+","+query+",32)");
            if(relation!=null) {
                conditions.parameters.addValue("relation",relation.key());
                conditions.where.add("ad.relation_tokens @@ plainto_tsquery('simple',:relation)");
            }
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static void address(Conditions conditions, String name, String value) {
        String normalized = text(value);
        if (normalized == null) return;
        // Match each supplied address term; do not let SQL wildcards broaden the region filter.
        String[] terms = terms(normalized);
        for (int i=0;i<terms.length;i++) {
            String key=name+i;
            conditions.where.add("lower(s.address) LIKE :"+key+" ESCAPE '!'");
            conditions.parameters.addValue(key,like(terms[i]));
        }
    }

    private static void addText(Conditions conditions, String name, String value,
                                Map<String,Integer> fields) {
        String[] terms = terms(value);
        List<String> tokenMatches = new ArrayList<>();
        for (int i=0;i<terms.length;i++) {
            String key = name+i;
            conditions.parameters.addValue(key,terms[i]).addValue(key+"Like",like(terms[i]));
            List<String> evidence = new ArrayList<>();
            for (Map.Entry<String,Integer> field : fields.entrySet()) {
                String column="lower(coalesce("+field.getKey()+",''))";
                String exact=column+" LIKE :"+key+"Like ESCAPE '!'";
                evidence.add(exact);
                conditions.scores.add("CASE WHEN "+exact+" THEN "+field.getValue()+" ELSE 0 END");
            }
            tokenMatches.add("CASE WHEN ("+String.join(" OR ",evidence)+") THEN 1 ELSE 0 END");
        }
        // Coverage across fields: a color and an ear marking may be stored in different columns.
        int required = terms.length;
        conditions.where.add("("+String.join(" + ",tokenMatches)+")>= "+required);
        conditions.parameters.addValue(name+"Phrase",like(value));
        for (Map.Entry<String,Integer> field : fields.entrySet())
            conditions.scores.add("CASE WHEN lower(coalesce("+field.getKey()+",'')) LIKE :"+name
                    +"Phrase ESCAPE '!' THEN "+field.getValue()+" ELSE 0 END");
    }

    private static void addBreed(Conditions conditions, String value) {
        // A literal partial name is the UI contract, not a spelling-similarity match.
        conditions.parameters.addValue("breedPart",like(value)).addValue("breedExact",value);
        conditions.where.add("lower(a.breed) LIKE :breedPart ESCAPE '!'");
        conditions.scores.add("CASE WHEN lower(a.breed)=:breedExact THEN 100 ELSE 0 END");
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized=value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length()>200) throw new IllegalArgumentException("검색어는 200자 이하여야 합니다.");
        return normalized;
    }
    private static String[] terms(String value) {
        String[] terms=value.split("\\s+");
        if (terms.length>8) throw new IllegalArgumentException("검색어는 8개 단어 이하여야 합니다.");
        return terms;
    }
    private static String like(String value) {
        return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    private static void validatePage(Pageable pageable) {
        if (pageable.isUnpaged() || pageable.getPageSize()>100)
            throw new IllegalArgumentException("페이지 크기는 1 이상 100 이하여야 합니다.");
        if (pageable.getSort().stream().count()>1)
            throw new IllegalArgumentException("정렬 기준은 하나만 지정할 수 있습니다.");
        // Keep the existing public offset contract until cursor pagination is introduced separately.
        if (pageable.getOffset()+pageable.getPageSize()>10_000)
            throw new IllegalArgumentException("검색 결과는 최대 10,000건까지만 조회할 수 있습니다. 검색 조건을 좁혀주세요.");
    }
    private static String ordering(Pageable pageable, boolean hasText) {
        Sort.Order order=pageable.getSort().isSorted()?pageable.getSort().iterator().next():Sort.Order.asc("noticeEndDate");
        String property=order.getProperty();
        if (property.equals("relevance")) {
            if (!hasText) throw new IllegalArgumentException("관련도순 정렬에는 키워드나 품종이 필요합니다.");
            return "relevance DESC,a.created_at DESC NULLS LAST,a.id ASC";
        }
        String column=switch(property) {
            case "createdAt", "created_at" -> "a.created_at";
            case "updatedAt", "updated_at" -> "a.updated_at";
            case "noticeEndDate", "notice_end_date" -> "a.notice_end_date";
            case "birthYear", "birth_year", "age" -> "a.birth_year";
            case "apmsDesertionNo", "apms_desertion_no" -> "a.apms_desertion_no";
            case "favoriteCount", "favorite_count" -> "a.favorite_count";
            default -> throw new IllegalArgumentException("지원하지 않는 정렬 기준입니다: "+property);
        };
        boolean ascending=order.isAscending();
        if (property.equals("age")) ascending=!ascending;
        return column+(ascending?" ASC":" DESC")+" NULLS LAST,a.id ASC";
    }
    private AnimalResponse response(ResultSet row, int index) throws SQLException {
        Integer year=row.getObject("birth_year",Integer.class);
        return AnimalResponse.builder().id(row.getLong("id")).apmsNoticeNo(row.getString("apms_notice_no"))
                .species(Species.valueOf(row.getString("species"))).breed(row.getString("breed"))
                .gender(Gender.valueOf(row.getString("gender"))).birthYear(year)
                .age(year==null?null:LocalDate.now().getYear()-year).specialMark(row.getString("special_mark"))
                .status(AnimalStatus.valueOf(row.getString("status")))
                .noticeEndDate(row.getObject("notice_end_date",LocalDate.class)).imageUrl(row.getString("image_url"))
                .favoriteCount(row.getObject("favorite_count",Integer.class)).shelterId(row.getLong("shelter_id"))
                .shelterName(row.getString("shelter_name")).createdAt(row.getObject("created_at",LocalDateTime.class)).build();
    }
    private static final class Conditions {
        private String from=FROM;
        private final List<String> where=new ArrayList<>();
        private final List<String> scores=new ArrayList<>();
        private final MapSqlParameterSource parameters=new MapSqlParameterSource();
        private void equal(String column,String key,Object value) {
            if (value==null) return;
            where.add(column+"=:"+key);
            parameters.addValue(key,value instanceof Enum<?> enumeration?enumeration.name():value);
        }
    }
}
