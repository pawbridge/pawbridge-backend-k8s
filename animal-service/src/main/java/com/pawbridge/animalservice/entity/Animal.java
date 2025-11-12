package com.pawbridge.animalservice.entity;

import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.NeuterStatus;
import com.pawbridge.animalservice.enums.Species;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;

/**
 * 유기동물 정보 엔티티
 * - APMS(동물보호관리시스템) API 응답 데이터를 기반으로 설계
 * - 실제 API 응답에서 자주 채워지는 필드만 포함 (27개)
 */
@Entity
@Table(name = "animals",
        indexes = {
                @Index(name = "idx_apms_desertion_no", columnList = "apmsDesertionNo", unique = true),
                @Index(name = "idx_species_status", columnList = "species,status"),
                @Index(name = "idx_notice_end_date", columnList = "noticeEndDate"),
                @Index(name = "idx_shelter_id", columnList = "shelter_id")
        })
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Animal {

    // ========== 기본 PK ==========
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========== APMS 핵심 식별 (2개) ==========
    /**
     * APMS 유기번호 (desertionNo)
     * - 예: "448567202501701"
     * - UNIQUE 제약: 중복 체크용
     * - APMS 배치 전용 (수동 등록 시 null)
     */
    @Column(unique = true, length = 50)
    private String apmsDesertionNo;

    /**
     * 공고번호 (noticeNo)
     * - 예: "경남-창원1-2025-00833", "경기-양평-2025-00429"
     * - APMS 배치 + 수동 등록 모두 사용
     * - UNIQUE 제약: 중복 방지
     */
    @Column(nullable = false, unique = true, length = 100)
    private String apmsNoticeNo;

    // ========== 동물 기본 정보 (9개) ==========
    /**
     * 축종 (upKindCd)
     * - 417000 → DOG, 422400 → CAT, 429900 → ETC
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Species species;

    /**
     * 품종명 (kindNm)
     * - 예: "믹스견", "라브라도 리트리버"
     */
    @Column(length = 100)
    private String breed;

    /**
     * 출생 연도
     * - age 필드에서 추출: "2023(년생)" → 2023
     * - "2025(60일미만)(년생)" → 2025
     */
    private Integer birthYear;

    /**
     * 체중 (weight)
     * - 예: "12(Kg)", "2.2(Kg)"
     * - String으로 저장 (단위 포함)
     */
    @Column(length = 50)
    private String weight;

    /**
     * 색상 (colorCd)
     * - 예: "옅은 황색", "검정&흰색", "갈색"
     */
    @Column(length = 100)
    private String color;

    /**
     * 성별 (sexCd)
     * - M → MALE, F → FEMALE, Q → UNKNOWN
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Gender gender;

    /**
     * 중성화 여부 (neuterYn)
     * - Y → YES, N → NO, U → UNKNOWN
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NeuterStatus neuterStatus;

    /**
     * 특징 (specialMark)
     * - 예: "엄청 까불까불하고 순해요~잘생김~보호자분도 애타게 찾으실듯..."
     */
    @Column(length = 1000)
    private String specialMark;

    // ========== APMS 공고 정보 (4개) ==========
    /**
     * APMS 진행상태 (processState)
     * - 예: "공고중", "보호중"
     * - APMS의 원본 상태 (자체 status와 분리)
     * - APMS 배치 전용
     */
    @Column(length = 50)
    private String apmsProcessState;

    /**
     * 공고 시작일 (noticeSdt)
     * - YYYYMMDD → LocalDate 변환
     * - APMS 배치 + 수동 등록 모두 사용 (필수)
     */
    @Column(nullable = false)
    private LocalDate noticeStartDate;

    /**
     * 공고 종료일 (noticeEdt)
     * - YYYYMMDD → LocalDate 변환
     * - 공고 종료 임박 정렬에 사용
     * - APMS 배치 + 수동 등록 모두 사용 (필수)
     */
    @Column(nullable = false)
    private LocalDate noticeEndDate;

    /**
     * APMS 마지막 수정 시각 (updTm)
     * - 예: "2025-11-09 17:34:36.0"
     * - 동기화 시 변경 감지에 유용
     */
    private LocalDateTime apmsUpdatedAt;

    // ========== 발견 정보 (2개) ==========
    /**
     * 접수일 (happenDt)
     * - YYYYMMDD → LocalDate 변환
     */
    private LocalDate happenDate;

    /**
     * 발견 장소 (happenPlace)
     * - 예: "진해구 남문동 남문시티 1차"
     */
    @Column(length = 200)
    private String happenPlace;

    // ========== 이미지 (2개) ==========
    /**
     * 대표 이미지 (popfile1)
     * - URL 형태
     */
    @Column(length = 500)
    private String imageUrl;

    /**
     * 추가 이미지 (popfile2)
     * - 실제 응답에서 거의 항상 포함됨
     */
    @Column(length = 500)
    private String imageUrl2;

    // ========== 보호소 연결 ==========
    /**
     * 보호소 FK
     * - careRegNo로 Shelter 조회 후 연결
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shelter_id", nullable = false)
    private Shelter shelter;

    // ========== 자체 관리 필드 (4개) ==========
    /**
     * 자체 관리 상태 (AnimalStatus)
     * - NOTICE, PROTECT, ADOPTION_PENDING, ADOPTED 등
     * - apmsProcessState와 분리 관리
     * - 예: APMS는 "공고중"이지만 우리 시스템에서는 "입양완료"
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnimalStatus status;

    /**
     * API 출처 (ApiSource)
     * - APMS_ANIMAL, GYEONGGI, MANUAL 등
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiSource apiSource;

    /**
     * 찜 횟수 (캐시)
     * - user-service의 favorite 이벤트로 업데이트
     */
    @Column(nullable = false)
    private Integer favoriteCount = 0;

    /**
     * 보호소가 추가 작성한 설명 (자체)
     * - APMS 데이터 외 보호소가 직접 입력
     */
    @Column(length = 2000)
    private String description;

    // ========== Audit (2개) ==========
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // ========== 비즈니스 메서드 ==========

    /**
     * 상태 변경
     * @param newStatus 새로운 상태
     */
    public void updateStatus(AnimalStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 찜 횟수 증가
     */
    public void incrementFavoriteCount() {
        this.favoriteCount++;
    }

    /**
     * 찜 횟수 감소
     */
    public void decrementFavoriteCount() {
        if (this.favoriteCount > 0) {
            this.favoriteCount--;
        }
    }

    /**
     * 나이 계산 (현재 연도 기준)
     * @return 만 나이
     */
    public Integer getAge() {
        if (birthYear == null) return null;
        return Year.now().getValue() - birthYear;
    }

    /**
     * 공고 종료 임박 여부
     * @return 3일 이내 종료 시 true
     */
    public boolean isNoticeExpiringSoon() {
        if (noticeEndDate == null) return false;
        return LocalDate.now().plusDays(3).isAfter(noticeEndDate);
    }

    /**
     * Shelter 설정
     * @param shelter 보호소
     */
    public void setShelter(Shelter shelter) {
        this.shelter = shelter;
    }

    // ========== 정적 팩토리 메서드 ==========

    /**
     * APMS 데이터로부터 Animal 생성 (기본값 설정)
     */
    public static Animal createFromApms(
            String apmsDesertionNo,
            String apmsNoticeNo,
            Species species,
            Gender gender,
            NeuterStatus neuterStatus,
            ApiSource apiSource,
            Shelter shelter
    ) {
        Animal animal = new Animal();
        animal.apmsDesertionNo = apmsDesertionNo;
        animal.apmsNoticeNo = apmsNoticeNo;
        animal.species = species;
        animal.gender = gender;
        animal.neuterStatus = neuterStatus;
        animal.apiSource = apiSource;
        animal.shelter = shelter;
        animal.status = AnimalStatus.NOTICE; // 초기 상태
        animal.favoriteCount = 0;
        return animal;
    }

    // ========== Setter 메서드 (ApmsDataMapper에서 사용) ==========

    public void setApmsDesertionNo(String apmsDesertionNo) {
        this.apmsDesertionNo = apmsDesertionNo;
    }

    public void setApmsNoticeNo(String apmsNoticeNo) {
        this.apmsNoticeNo = apmsNoticeNo;
    }

    public void setSpecies(Species species) {
        this.species = species;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public void setBirthYear(Integer birthYear) {
        this.birthYear = birthYear;
    }

    public void setWeight(String weight) {
        this.weight = weight;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public void setNeuterStatus(NeuterStatus neuterStatus) {
        this.neuterStatus = neuterStatus;
    }

    public void setSpecialMark(String specialMark) {
        this.specialMark = specialMark;
    }

    public void setApmsProcessState(String apmsProcessState) {
        this.apmsProcessState = apmsProcessState;
    }

    public void setNoticeStartDate(LocalDate noticeStartDate) {
        this.noticeStartDate = noticeStartDate;
    }

    public void setNoticeEndDate(LocalDate noticeEndDate) {
        this.noticeEndDate = noticeEndDate;
    }

    public void setApmsUpdatedAt(LocalDateTime apmsUpdatedAt) {
        this.apmsUpdatedAt = apmsUpdatedAt;
    }

    public void setHappenDate(LocalDate happenDate) {
        this.happenDate = happenDate;
    }

    public void setHappenPlace(String happenPlace) {
        this.happenPlace = happenPlace;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public void setImageUrl2(String imageUrl2) {
        this.imageUrl2 = imageUrl2;
    }

    public void setStatus(AnimalStatus status) {
        this.status = status;
    }

    public void setApiSource(ApiSource apiSource) {
        this.apiSource = apiSource;
    }

    public void setFavoriteCount(Integer favoriteCount) {
        this.favoriteCount = favoriteCount;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
