package com.pawbridge.animalservice.specification;

import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;

/**
 * Animal 동적 검색 Specification
 * - Phase 1: MySQL 기반 임시 구현
 * - Phase 4: OpenSearch 전환 시 제거 예정
 */
public class AnimalSpecification {

    /**
     * AnimalSearchRequest 기반 동적 쿼리 생성
     *
     * @param request 검색 조건
     * @return Specification<Animal>
     */
    public static Specification<Animal> searchAnimals(AnimalSearchRequest request) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. 축종 필터
            if (request.getSpecies() != null) {
                predicates.add(criteriaBuilder.equal(root.get("species"), request.getSpecies()));
            }

            // 2. 품종 필터 (부분 검색)
            if (request.getBreed() != null && !request.getBreed().isBlank()) {
                predicates.add(criteriaBuilder.like(root.get("breed"), "%" + request.getBreed() + "%"));
            }

            // 3. 성별 필터
            if (request.getGender() != null) {
                predicates.add(criteriaBuilder.equal(root.get("gender"), request.getGender()));
            }

            // 4. 중성화 여부 필터
            if (request.getNeuterStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("neuterStatus"), request.getNeuterStatus()));
            }

            // 5. 상태 필터
            if (request.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), request.getStatus()));
            }

            // 6. 나이 범위 필터 (birthYear 계산)
            if (request.getMinAge() != null || request.getMaxAge() != null) {
                int currentYear = Year.now().getValue();

                // minAge=1 → birthYear <= 현재-1 (1살 이상)
                if (request.getMinAge() != null) {
                    int maxBirthYear = currentYear - request.getMinAge();
                    predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("birthYear"), maxBirthYear));
                }

                // maxAge=5 → birthYear >= 현재-5 (5살 이하)
                if (request.getMaxAge() != null) {
                    int minBirthYear = currentYear - request.getMaxAge();
                    predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("birthYear"), minBirthYear));
                }
            }

            // 7. 지역 필터 (Shelter.address)
            if ((request.getRegion() != null && !request.getRegion().isBlank()) ||
                    (request.getCity() != null && !request.getCity().isBlank())) {

                Join<Animal, Shelter> shelterJoin = root.join("shelter", JoinType.INNER);

                // 시도 검색 (예: "서울", "경기")
                if (request.getRegion() != null && !request.getRegion().isBlank()) {
                    predicates.add(criteriaBuilder.like(shelterJoin.get("address"), "%" + request.getRegion() + "%"));
                }

                // 시군구 검색 (예: "강남구", "수원시")
                if (request.getCity() != null && !request.getCity().isBlank()) {
                    predicates.add(criteriaBuilder.like(shelterJoin.get("address"), "%" + request.getCity() + "%"));
                }
            }

            // 8. 키워드 통합 검색 (품종 OR 특징 OR 발견장소)
            if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
                String keywordPattern = "%" + request.getKeyword() + "%";

                Predicate breedLike = criteriaBuilder.like(root.get("breed"), keywordPattern);
                Predicate specialMarkLike = criteriaBuilder.like(root.get("specialMark"), keywordPattern);
                Predicate happenPlaceLike = criteriaBuilder.like(root.get("happenPlace"), keywordPattern);

                predicates.add(criteriaBuilder.or(breedLike, specialMarkLike, happenPlaceLike));
            }

            // 9. 모든 조건 AND로 결합
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Shelter를 fetch join하는 Specification
     * - N+1 방지용
     * - 검색 조건과 조합하여 사용
     *
     * @return Specification<Animal>
     */
    public static Specification<Animal> fetchShelter() {
        return (root, query, criteriaBuilder) -> {
            // 중복 fetch 방지: count 쿼리에서는 fetch join 제외
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("shelter", JoinType.LEFT);
            }
            return criteriaBuilder.conjunction(); // 조건 없음 (항상 true)
        };
    }
}
