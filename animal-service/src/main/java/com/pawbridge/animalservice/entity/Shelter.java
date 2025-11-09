package com.pawbridge.animalservice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 보호소 정보 엔티티
 * - APMS API 응답의 보호소 관련 필드를 기반으로 설계
 */
@Entity
@Table(name = "shelters",
        indexes = {
                @Index(name = "idx_care_reg_no", columnList = "careRegNo", unique = true)
        })
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shelter {

    // ========== 기본 PK ==========
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========== APMS 보호소 식별 ==========
    /**
     * 보호소 등록번호 (careRegNo)
     * - 예: "348527200900001"
     * - APMS에서 제공하는 보호소 고유 번호
     * - UNIQUE 제약
     */
    @Column(nullable = false, unique = true, length = 50)
    private String careRegNo;

    // ========== 보호소 기본 정보 ==========
    /**
     * 보호소 이름 (careNm)
     * - 예: "창원동물보호센터"
     */
    @Column(nullable = false, length = 200)
    private String name;

    /**
     * 보호소 전화번호 (careTel)
     * - 예: "055-225-5701"
     */
    @Column(length = 50)
    private String phone;

    /**
     * 보호소 주소 (careAddr)
     * - 예: "경상남도 창원시 성산구 공단로474번길 117 (상복동) 창원동물보호센터"
     */
    @Column(length = 500)
    private String address;

    /**
     * 보호소 대표자 (careOwnerNm)
     * - 예: "창원시장"
     */
    @Column(length = 100)
    private String ownerName;

    /**
     * 관할 기관 (orgNm)
     * - 예: "경상남도 창원시 의창성산구"
     */
    @Column(length = 200)
    private String organizationName;

    // ========== 자체 추가 정보 (보호소 회원이 직접 입력) ==========
    /**
     * 보호소 이메일 (자체 관리)
     * - APMS에는 없지만 우리 시스템에서 관리
     */
    @Column(length = 100)
    private String email;

    /**
     * 보호소 소개 (자체 관리)
     */
    @Column(length = 2000)
    private String introduction;

    /**
     * 입양 절차 안내 (자체 관리)
     */
    @Column(length = 2000)
    private String adoptionProcedure;

    /**
     * 운영 시간 (자체 관리)
     * - 예: "평일 09:00-18:00, 주말 10:00-17:00"
     */
    @Column(length = 200)
    private String operatingHours;

    // ========== 관계 ==========
    /**
     * 보호 중인 동물 목록
     * - mappedBy: Animal.shelter 필드와 연결
     * - cascade = PERSIST: Animal 저장 시 새로운 Shelter도 함께 저장 (배치 작업에서 유용)
     * - orphanRemoval = false: Animal에서 shelter 참조 제거해도 Shelter는 유지
     *   (같은 Shelter를 여러 Animal이 참조할 수 있으므로)
     */
    @OneToMany(mappedBy = "shelter", cascade = CascadeType.PERSIST, orphanRemoval = false)
    private List<Animal> animals = new ArrayList<>();

    // ========== Audit ==========
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // ========== 비즈니스 메서드 ==========

    /**
     * 동물 추가
     * @param animal 동물
     */
    public void addAnimal(Animal animal) {
        this.animals.add(animal);
        animal.setShelter(this);
    }

    /**
     * 동물 제거
     * @param animal 동물
     */
    public void removeAnimal(Animal animal) {
        this.animals.remove(animal);
        animal.setShelter(null);
    }

    /**
     * 보호소 정보 수정 (보호소 회원이 직접 수정)
     */
    public void updateInfo(
            String phone,
            String email,
            String introduction,
            String adoptionProcedure,
            String operatingHours
    ) {
        this.phone = phone;
        this.email = email;
        this.introduction = introduction;
        this.adoptionProcedure = adoptionProcedure;
        this.operatingHours = operatingHours;
    }

    // ========== 정적 팩토리 메서드 ==========

    /**
     * APMS 데이터로부터 Shelter 생성
     */
    public static Shelter createFromApms(
            String careRegNo,
            String name,
            String phone,
            String address,
            String ownerName,
            String organizationName
    ) {
        Shelter shelter = new Shelter();
        shelter.careRegNo = careRegNo;
        shelter.name = name;
        shelter.phone = phone;
        shelter.address = address;
        shelter.ownerName = ownerName;
        shelter.organizationName = organizationName;
        return shelter;
    }

    // ========== Setter 메서드 (필요 시) ==========

    public void setCareRegNo(String careRegNo) {
        this.careRegNo = careRegNo;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setIntroduction(String introduction) {
        this.introduction = introduction;
    }

    public void setAdoptionProcedure(String adoptionProcedure) {
        this.adoptionProcedure = adoptionProcedure;
    }

    public void setOperatingHours(String operatingHours) {
        this.operatingHours = operatingHours;
    }
}
