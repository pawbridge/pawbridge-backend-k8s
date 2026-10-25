package com.pawbridge.backend.animal.internal.command.infrastructure.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

/**
 * 보호소 정보 엔티티
 * - APMS API 응답의 보호소 관련 필드를 기반으로 설계
 */
@Entity
@Table(
    name = "shelters",
    schema = "pawbridge_animal",
    indexes = [
        Index(name = "idx_care_reg_no", columnList = "careRegNo", unique = true),
    ],
)
class Shelter private constructor(
    id: Long?,
    careRegNo: String?,
    name: String?,
    phone: String?,
    address: String?,
    ownerName: String?,
    organizationName: String?,
    email: String?,
    introduction: String?,
    adoptionProcedure: String?,
    operatingHours: String?,
    animals: MutableList<Animal>?,
) : BaseTimeEntity() {

    // 기본 PK
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    // APMS 보호소 식별
    /**
     * 보호소 등록번호 (careRegNo)
     * - 예: "348527200900001"
     * - APMS에서 제공하는 보호소 고유 번호
     * - UNIQUE 제약
     */
    @Column(nullable = false, unique = true, length = 50)
    var careRegNo: String? = careRegNo
        protected set

    // 보호소 기본 정보
    /**
     * 보호소 이름 (careNm)
     * - 예: "창원동물보호센터"
     */
    @Column(nullable = false, length = 200)
    var name: String? = name
        protected set

    /**
     * 보호소 전화번호 (careTel)
     * - 예: "055-225-5701"
     */
    @Column(length = 50)
    var phone: String? = phone
        protected set

    /**
     * 보호소 주소 (careAddr)
     * - 예: "경상남도 창원시 성산구 공단로474번길 117 (상복동) 창원동물보호센터"
     */
    @Column(length = 500)
    var address: String? = address
        protected set

    /**
     * 보호소 대표자 (careOwnerNm)
     * - 예: "창원시장"
     */
    @Column(length = 100)
    var ownerName: String? = ownerName
        protected set

    /**
     * 관할 기관 (orgNm)
     * - 예: "경상남도 창원시 의창성산구"
     */
    @Column(length = 200)
    var organizationName: String? = organizationName
        protected set

    // 자체 추가 정보 (보호소 회원이 직접 입력)
    /**
     * 보호소 이메일 (자체 관리)
     * - APMS에는 없지만 우리 시스템에서 관리
     */
    @Column(length = 100)
    var email: String? = email
        protected set

    /**
     * 보호소 소개 (자체 관리)
     */
    @Column(length = 2000)
    var introduction: String? = introduction
        protected set

    /**
     * 입양 절차 안내 (자체 관리)
     */
    @Column(length = 2000)
    var adoptionProcedure: String? = adoptionProcedure
        protected set

    /**
     * 운영 시간 (자체 관리)
     * - 예: "평일 09:00-18:00, 주말 10:00-17:00"
     */
    @Column(length = 200)
    var operatingHours: String? = operatingHours
        protected set

    // 관계
    /**
     * 보호 중인 동물 목록
     * - mappedBy: Animal.shelter 필드와 연결
     * - cascade = PERSIST: Animal 저장 시 새로운 Shelter도 함께 저장 (배치 작업에서 유용)
     * - orphanRemoval = false: Animal에서 shelter 참조 제거해도 Shelter는 유지
     * (같은 Shelter를 여러 Animal이 참조할 수 있으므로)
     */
    @OneToMany(mappedBy = "shelter", cascade = [CascadeType.PERSIST], orphanRemoval = false)
    var animals: MutableList<Animal>? = animals
        protected set

    // Hibernate와 Java 호출부가 쓰는 기본 생성자. Lombok의 @Builder.Default처럼 animals는 빈 목록으로 시작한다.
    protected constructor() : this(
        id = null,
        careRegNo = null,
        name = null,
        phone = null,
        address = null,
        ownerName = null,
        organizationName = null,
        email = null,
        introduction = null,
        adoptionProcedure = null,
        operatingHours = null,
        animals = ArrayList(),
    )

    // 비즈니스 메서드

    /**
     * 동물 추가
     *
     * @param animal 동물
     */
    fun addAnimal(animal: Animal) {
        this.animals!!.add(animal)
        animal.assignShelter(this)
    }

    /**
     * 동물 제거
     *
     * @param animal 동물
     */
    fun removeAnimal(animal: Animal) {
        this.animals!!.remove(animal)
        animal.assignShelter(null)
    }

    /**
     * 보호소 정보 수정 (보호소 회원이 직접 수정)
     */
    fun updateInfo(
        phone: String?,
        email: String?,
        introduction: String?,
        adoptionProcedure: String?,
        operatingHours: String?,
    ) {
        this.phone = phone
        this.email = email
        this.introduction = introduction
        this.adoptionProcedure = adoptionProcedure
        this.operatingHours = operatingHours
    }

    class ShelterBuilder internal constructor() {
        private var id: Long? = null
        private var careRegNo: String? = null
        private var name: String? = null
        private var phone: String? = null
        private var address: String? = null
        private var ownerName: String? = null
        private var organizationName: String? = null
        private var email: String? = null
        private var introduction: String? = null
        private var adoptionProcedure: String? = null
        private var operatingHours: String? = null
        private var animals: MutableList<Animal>? = ArrayList()

        fun id(id: Long?): ShelterBuilder = apply {
            this.id = id
        }

        fun careRegNo(careRegNo: String?): ShelterBuilder = apply {
            this.careRegNo = careRegNo
        }

        fun name(name: String?): ShelterBuilder = apply {
            this.name = name
        }

        fun phone(phone: String?): ShelterBuilder = apply {
            this.phone = phone
        }

        fun address(address: String?): ShelterBuilder = apply {
            this.address = address
        }

        fun ownerName(ownerName: String?): ShelterBuilder = apply {
            this.ownerName = ownerName
        }

        fun organizationName(organizationName: String?): ShelterBuilder = apply {
            this.organizationName = organizationName
        }

        fun email(email: String?): ShelterBuilder = apply {
            this.email = email
        }

        fun introduction(introduction: String?): ShelterBuilder = apply {
            this.introduction = introduction
        }

        fun adoptionProcedure(adoptionProcedure: String?): ShelterBuilder = apply {
            this.adoptionProcedure = adoptionProcedure
        }

        fun operatingHours(operatingHours: String?): ShelterBuilder = apply {
            this.operatingHours = operatingHours
        }

        fun animals(animals: MutableList<Animal>?): ShelterBuilder = apply {
            this.animals = animals
        }

        fun build(): Shelter = Shelter(
            id,
            careRegNo,
            name,
            phone,
            address,
            ownerName,
            organizationName,
            email,
            introduction,
            adoptionProcedure,
            operatingHours,
            animals,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): ShelterBuilder = ShelterBuilder()

        // 정적 팩토리 메서드

        /**
         * APMS 데이터로부터 Shelter 생성
         */
        @JvmStatic
        fun createFromApms(
            careRegNo: String?,
            name: String?,
            phone: String?,
            address: String?,
            ownerName: String?,
            organizationName: String?,
        ): Shelter {
            return builder()
                .careRegNo(careRegNo)
                .name(name)
                .phone(phone)
                .address(address)
                .ownerName(ownerName)
                .organizationName(organizationName)
                .animals(ArrayList())
                .build()
        }
    }
}
