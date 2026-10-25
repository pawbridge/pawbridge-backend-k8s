package com.pawbridge.backend.animal.internal.apms.infrastructure.dto

/**
 * APMS 유기동물 조회 API 응답 item
 * - 모든 필드는 String (변환은 Processor에서 처리)
 */
class ApmsAnimal() {

    // 핵심 식별 정보
    /**
     * 유기번호 (UNIQUE)
     * - 예: "448121202500068"
     */
    var desertionNo: String? = null

    /**
     * 공고번호
     * - 예: "경기-양평-2025-00429"
     */
    var noticeNo: String? = null

    // 동물 기본 정보
    /**
     * 축종코드
     * - 417000: 개
     * - 422400: 고양이
     * - 429900: 기타
     */
    var upKindCd: String? = null

    /**
     * 품종코드
     * - 예: "[개] 믹스견"
     */
    var kindCd: String? = null

    /**
     * 품종명
     * - 예: "믹스견", "페르시안"
     */
    var kindNm: String? = null

    /**
     * 나이
     * - 예: "2023(년생)"
     */
    var age: String? = null

    /**
     * 체중
     * - 예: "12(Kg)"
     */
    var weight: String? = null

    /**
     * 색상
     * - 예: "옅은 황색", "검정&흰색"
     */
    var colorCd: String? = null

    /**
     * 성별
     * - M: 수컷
     * - F: 암컷
     * - Q: 미상
     */
    var sexCd: String? = null

    /**
     * 중성화 여부
     * - Y: 예
     * - N: 아니오
     * - U: 미상
     */
    var neuterYn: String? = null

    /**
     * 특징
     * - 예: "엄청 까불까불하고 순해요~잘생김~"
     */
    var specialMark: String? = null

    // APMS 공고 정보
    /**
     * 상태
     * - "공고중", "보호중" 등
     */
    var processState: String? = null

    /**
     * 공고시작일
     * - 형식: YYYYMMDD
     */
    var noticeSdt: String? = null

    /**
     * 공고종료일
     * - 형식: YYYYMMDD
     */
    var noticeEdt: String? = null

    /**
     * 수정일시
     * - ISO 형식
     * - 예: "2025-11-09 17:34:36.0"
     */
    var updTm: String? = null

    // 발견 정보
    /**
     * 접수일
     * - 형식: YYYYMMDD
     */
    var happenDt: String? = null

    /**
     * 발견장소
     * - 예: "진해구 남문동 남문시티 1차"
     */
    var happenPlace: String? = null

    // 이미지
    /**
     * 대표 이미지 URL
     * - popfile1
     */
    var popfile1: String? = null

    /**
     * 추가 이미지 URL
     * - popfile2
     */
    var popfile2: String? = null

    // 보호소 정보
    /**
     * 보호소번호
     * - 예: "348527200900001"
     */
    var careRegNo: String? = null

    /**
     * 보호소명
     * - 예: "창원동물보호센터"
     */
    var careNm: String? = null

    /**
     * 보호소 전화번호
     * - 예: "055-225-5701"
     */
    var careTel: String? = null

    /**
     * 보호소 주소
     * - 예: "경상남도 창원시 성산구 공단로474번길 117"
     */
    var careAddr: String? = null

    /**
     * 관할기관
     * - 예: "경상남도 창원시 의창성산구"
     */
    var orgNm: String? = null

    /**
     * 담당자
     * - 예: "창원시장"
     */
    var chargeNm: String? = null

    /**
     * 담당자 연락처
     */
    var officetel: String? = null

    // Lombok @AllArgsConstructor와 같은 인자 순서(필드 선언 순서)
    constructor(
        desertionNo: String?,
        noticeNo: String?,
        upKindCd: String?,
        kindCd: String?,
        kindNm: String?,
        age: String?,
        weight: String?,
        colorCd: String?,
        sexCd: String?,
        neuterYn: String?,
        specialMark: String?,
        processState: String?,
        noticeSdt: String?,
        noticeEdt: String?,
        updTm: String?,
        happenDt: String?,
        happenPlace: String?,
        popfile1: String?,
        popfile2: String?,
        careRegNo: String?,
        careNm: String?,
        careTel: String?,
        careAddr: String?,
        orgNm: String?,
        chargeNm: String?,
        officetel: String?,
    ) : this() {
        this.desertionNo = desertionNo
        this.noticeNo = noticeNo
        this.upKindCd = upKindCd
        this.kindCd = kindCd
        this.kindNm = kindNm
        this.age = age
        this.weight = weight
        this.colorCd = colorCd
        this.sexCd = sexCd
        this.neuterYn = neuterYn
        this.specialMark = specialMark
        this.processState = processState
        this.noticeSdt = noticeSdt
        this.noticeEdt = noticeEdt
        this.updTm = updTm
        this.happenDt = happenDt
        this.happenPlace = happenPlace
        this.popfile1 = popfile1
        this.popfile2 = popfile2
        this.careRegNo = careRegNo
        this.careNm = careNm
        this.careTel = careTel
        this.careAddr = careAddr
        this.orgNm = orgNm
        this.chargeNm = chargeNm
        this.officetel = officetel
    }

    // Lombok @Data의 값 기반 equals/hashCode/toString
    private fun fieldValues(): List<Any?> {
        return listOf(
            desertionNo,
            noticeNo,
            upKindCd,
            kindCd,
            kindNm,
            age,
            weight,
            colorCd,
            sexCd,
            neuterYn,
            specialMark,
            processState,
            noticeSdt,
            noticeEdt,
            updTm,
            happenDt,
            happenPlace,
            popfile1,
            popfile2,
            careRegNo,
            careNm,
            careTel,
            careAddr,
            orgNm,
            chargeNm,
            officetel,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ApmsAnimal) {
            return false
        }
        return fieldValues() == other.fieldValues()
    }

    override fun hashCode(): Int = fieldValues().hashCode()

    override fun toString(): String {
        return "ApmsAnimal(desertionNo=$desertionNo, noticeNo=$noticeNo, upKindCd=$upKindCd, kindCd=$kindCd, " +
            "kindNm=$kindNm, age=$age, weight=$weight, colorCd=$colorCd, sexCd=$sexCd, neuterYn=$neuterYn, " +
            "specialMark=$specialMark, processState=$processState, noticeSdt=$noticeSdt, noticeEdt=$noticeEdt, " +
            "updTm=$updTm, happenDt=$happenDt, happenPlace=$happenPlace, popfile1=$popfile1, popfile2=$popfile2, " +
            "careRegNo=$careRegNo, careNm=$careNm, careTel=$careTel, careAddr=$careAddr, orgNm=$orgNm, " +
            "chargeNm=$chargeNm, officetel=$officetel)"
    }

    class ApmsAnimalBuilder internal constructor() {
        private var desertionNo: String? = null
        private var noticeNo: String? = null
        private var upKindCd: String? = null
        private var kindCd: String? = null
        private var kindNm: String? = null
        private var age: String? = null
        private var weight: String? = null
        private var colorCd: String? = null
        private var sexCd: String? = null
        private var neuterYn: String? = null
        private var specialMark: String? = null
        private var processState: String? = null
        private var noticeSdt: String? = null
        private var noticeEdt: String? = null
        private var updTm: String? = null
        private var happenDt: String? = null
        private var happenPlace: String? = null
        private var popfile1: String? = null
        private var popfile2: String? = null
        private var careRegNo: String? = null
        private var careNm: String? = null
        private var careTel: String? = null
        private var careAddr: String? = null
        private var orgNm: String? = null
        private var chargeNm: String? = null
        private var officetel: String? = null

        fun desertionNo(desertionNo: String?): ApmsAnimalBuilder = apply {
            this.desertionNo = desertionNo
        }

        fun noticeNo(noticeNo: String?): ApmsAnimalBuilder = apply {
            this.noticeNo = noticeNo
        }

        fun upKindCd(upKindCd: String?): ApmsAnimalBuilder = apply {
            this.upKindCd = upKindCd
        }

        fun kindCd(kindCd: String?): ApmsAnimalBuilder = apply {
            this.kindCd = kindCd
        }

        fun kindNm(kindNm: String?): ApmsAnimalBuilder = apply {
            this.kindNm = kindNm
        }

        fun age(age: String?): ApmsAnimalBuilder = apply {
            this.age = age
        }

        fun weight(weight: String?): ApmsAnimalBuilder = apply {
            this.weight = weight
        }

        fun colorCd(colorCd: String?): ApmsAnimalBuilder = apply {
            this.colorCd = colorCd
        }

        fun sexCd(sexCd: String?): ApmsAnimalBuilder = apply {
            this.sexCd = sexCd
        }

        fun neuterYn(neuterYn: String?): ApmsAnimalBuilder = apply {
            this.neuterYn = neuterYn
        }

        fun specialMark(specialMark: String?): ApmsAnimalBuilder = apply {
            this.specialMark = specialMark
        }

        fun processState(processState: String?): ApmsAnimalBuilder = apply {
            this.processState = processState
        }

        fun noticeSdt(noticeSdt: String?): ApmsAnimalBuilder = apply {
            this.noticeSdt = noticeSdt
        }

        fun noticeEdt(noticeEdt: String?): ApmsAnimalBuilder = apply {
            this.noticeEdt = noticeEdt
        }

        fun updTm(updTm: String?): ApmsAnimalBuilder = apply {
            this.updTm = updTm
        }

        fun happenDt(happenDt: String?): ApmsAnimalBuilder = apply {
            this.happenDt = happenDt
        }

        fun happenPlace(happenPlace: String?): ApmsAnimalBuilder = apply {
            this.happenPlace = happenPlace
        }

        fun popfile1(popfile1: String?): ApmsAnimalBuilder = apply {
            this.popfile1 = popfile1
        }

        fun popfile2(popfile2: String?): ApmsAnimalBuilder = apply {
            this.popfile2 = popfile2
        }

        fun careRegNo(careRegNo: String?): ApmsAnimalBuilder = apply {
            this.careRegNo = careRegNo
        }

        fun careNm(careNm: String?): ApmsAnimalBuilder = apply {
            this.careNm = careNm
        }

        fun careTel(careTel: String?): ApmsAnimalBuilder = apply {
            this.careTel = careTel
        }

        fun careAddr(careAddr: String?): ApmsAnimalBuilder = apply {
            this.careAddr = careAddr
        }

        fun orgNm(orgNm: String?): ApmsAnimalBuilder = apply {
            this.orgNm = orgNm
        }

        fun chargeNm(chargeNm: String?): ApmsAnimalBuilder = apply {
            this.chargeNm = chargeNm
        }

        fun officetel(officetel: String?): ApmsAnimalBuilder = apply {
            this.officetel = officetel
        }

        fun build(): ApmsAnimal = ApmsAnimal(
            desertionNo,
            noticeNo,
            upKindCd,
            kindCd,
            kindNm,
            age,
            weight,
            colorCd,
            sexCd,
            neuterYn,
            specialMark,
            processState,
            noticeSdt,
            noticeEdt,
            updTm,
            happenDt,
            happenPlace,
            popfile1,
            popfile2,
            careRegNo,
            careNm,
            careTel,
            careAddr,
            orgNm,
            chargeNm,
            officetel,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): ApmsAnimalBuilder = ApmsAnimalBuilder()
    }
}
