package com.pawbridge.animalservice.dto.apms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * APMS 유기동물 조회 API 응답 item
 * - 모든 필드는 String (변환은 Processor에서 처리)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmsAnimal {

    // 핵심 식별 정보
    /**
     * 유기번호 (UNIQUE)
     * - 예: "448121202500068"
     */
    private String desertionNo;

    /**
     * 공고번호
     * - 예: "경기-양평-2025-00429"
     */
    private String noticeNo;

    // 동물 기본 정보
    /**
     * 축종코드
     * - 417000: 개
     * - 422400: 고양이
     * - 429900: 기타
     */
    private String upKindCd;

    /**
     * 품종코드
     * - 예: "[개] 믹스견"
     */
    private String kindCd;

    /**
     * 품종명
     * - 예: "믹스견", "페르시안"
     */
    private String kindNm;

    /**
     * 나이
     * - 예: "2023(년생)"
     */
    private String age;

    /**
     * 체중
     * - 예: "12(Kg)"
     */
    private String weight;

    /**
     * 색상
     * - 예: "옅은 황색", "검정&흰색"
     */
    private String colorCd;

    /**
     * 성별
     * - M: 수컷
     * - F: 암컷
     * - Q: 미상
     */
    private String sexCd;

    /**
     * 중성화 여부
     * - Y: 예
     * - N: 아니오
     * - U: 미상
     */
    private String neuterYn;

    /**
     * 특징
     * - 예: "엄청 까불까불하고 순해요~잘생김~"
     */
    private String specialMark;

    // APMS 공고 정보
    /**
     * 상태
     * - "공고중", "보호중" 등
     */
    private String processState;

    /**
     * 공고시작일
     * - 형식: YYYYMMDD
     */
    private String noticeSdt;

    /**
     * 공고종료일
     * - 형식: YYYYMMDD
     */
    private String noticeEdt;

    /**
     * 수정일시
     * - ISO 형식
     * - 예: "2025-11-09 17:34:36.0"
     */
    private String updTm;

    // 발견 정보
    /**
     * 접수일
     * - 형식: YYYYMMDD
     */
    private String happenDt;

    /**
     * 발견장소
     * - 예: "진해구 남문동 남문시티 1차"
     */
    private String happenPlace;

    // 이미지
    /**
     * 대표 이미지 URL
     * - popfile1
     */
    private String popfile1;

    /**
     * 추가 이미지 URL
     * - popfile2
     */
    private String popfile2;

    // 보호소 정보
    /**
     * 보호소번호
     * - 예: "348527200900001"
     */
    private String careRegNo;

    /**
     * 보호소명
     * - 예: "창원동물보호센터"
     */
    private String careNm;

    /**
     * 보호소 전화번호
     * - 예: "055-225-5701"
     */
    private String careTel;

    /**
     * 보호소 주소
     * - 예: "경상남도 창원시 성산구 공단로474번길 117"
     */
    private String careAddr;

    /**
     * 관할기관
     * - 예: "경상남도 창원시 의창성산구"
     */
    private String orgNm;

    /**
     * 담당자
     * - 예: "창원시장"
     */
    private String chargeNm;

    /**
     * 담당자 연락처
     */
    private String officetel;
}
