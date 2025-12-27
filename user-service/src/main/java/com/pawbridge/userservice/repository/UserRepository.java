package com.pawbridge.userservice.repository;

import com.pawbridge.userservice.dto.response.DailySignupStatsResponse;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * 이메일과 Provider로 사용자 조회
     * OAuth2 로그인 시 사용 (LOCAL과 GOOGLE을 구분하기 위함)
     */
    Optional<User> findByEmailAndProvider(String email, String provider);

    /**
     * Provider와 ProviderId로 사용자 조회
     * OAuth2 사용자 식별용 (향후 확장 가능성)
     */
    Optional<User> findByProviderAndProviderId(String provider, String providerId);

    /**
     * 이메일과 provider로 존재 여부 확인
     */
    boolean existsByEmailAndProvider(String email, String provider);

    /**
     * 닉네임 중복 확인
     */
    boolean existsByNickname(String nickname);

    /**
     * 닉네임으로 사용자 조회
     */
    Optional<User> findByNickname(String nickname);

    /**
     * 일별 가입자 수 통계 (관리자용)
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 일별 가입자 수 목록
     */
    @Query("SELECT new com.pawbridge.userservice.dto.response.DailySignupStatsResponse(" +
           "CAST(u.createdAt AS LocalDate), COUNT(u)) " +
           "FROM User u " +
           "WHERE CAST(u.createdAt AS LocalDate) BETWEEN :startDate AND :endDate " +
           "GROUP BY CAST(u.createdAt AS LocalDate) " +
           "ORDER BY CAST(u.createdAt AS LocalDate)")
    List<DailySignupStatsResponse> countDailySignups(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 회원 검색 (관리자용)
     * - keyword로 이메일, 이름, 닉네임 검색
     * - role로 필터링 (선택적)
     * @param keyword 검색 키워드 (이메일, 이름, 닉네임)
     * @param role 역할 필터 (null이면 전체)
     * @param pageable 페이징 정보
     * @return 검색된 회원 목록
     */
    @Query("SELECT u FROM User u WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND " +
           "(:role IS NULL OR u.role = :role)")
    Page<User> searchUsers(
            @Param("keyword") String keyword,
            @Param("role") Role role,
            Pageable pageable);

}
