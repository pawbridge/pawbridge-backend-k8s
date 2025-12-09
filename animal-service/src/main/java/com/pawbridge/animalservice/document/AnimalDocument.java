package com.pawbridge.animalservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * Elasticsearch용 동물 문서
 * - Debezium CDC를 통해 자동 동기화
 * - Animal 엔티티와 동일한 구조 유지
 * - 인덱스 필드명은 snake_case (DB 컬럼명과 동일)
 * - 날짜 필드는 String(Keyword)으로 매핑 (답변2 B안)
 * - ID 필드는 esId(_id)와 id(PK) 분리 (답변3 B안)
 */
@Document(indexName = "animals")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalDocument {

    @Id
    private String esId; // Elasticsearch Document ID (_id)

    @Field(name = "id", type = FieldType.Long)
    private Long id; // MySQL PK (id)

    // APMS 핵심 식별
    @Field(name = "apms_desertion_no", type = FieldType.Keyword)
    private String apmsDesertionNo;

    @Field(name = "apms_notice_no", type = FieldType.Keyword)
    private String apmsNoticeNo;

    // 동물 기본 정보
    @Field(name = "species", type = FieldType.Keyword)
    private String species;

    @Field(name = "breed", type = FieldType.Text)
    private String breed;

    @Field(name = "birth_year", type = FieldType.Integer)
    private Integer birthYear;

    @Field(name = "weight", type = FieldType.Keyword)
    private String weight;

    @Field(name = "color", type = FieldType.Text)
    private String color;

    @Field(name = "gender", type = FieldType.Keyword)
    private String gender;

    @Field(name = "neuter_status", type = FieldType.Keyword)
    private String neuterStatus;

    @Field(name = "special_mark", type = FieldType.Text)
    private String specialMark;

    // APMS 공고 정보
    @Field(name = "apms_process_state", type = FieldType.Keyword)
    private String apmsProcessState;

    @Field(name = "notice_start_date", type = FieldType.Keyword)
    private String noticeStartDate;

    @Field(name = "notice_end_date", type = FieldType.Keyword)
    private String noticeEndDate;

    @Field(name = "apms_updated_at", type = FieldType.Keyword)
    private String apmsUpdatedAt;

    // 발견 정보
    @Field(name = "happen_date", type = FieldType.Keyword)
    private String happenDate;

    @Field(name = "happen_place", type = FieldType.Text)
    private String happenPlace;

    // 이미지
    @Field(name = "image_url", type = FieldType.Keyword)
    private String imageUrl;

    @Field(name = "image_url2", type = FieldType.Keyword)
    private String imageUrl2;

    // 보호소 정보
    @Field(name = "shelter_id", type = FieldType.Long)
    private Long shelterId;

    @Field(name = "shelter_name", type = FieldType.Text)
    private String shelterName;

    @Field(name = "shelter_address", type = FieldType.Text)
    private String shelterAddress;

    @Field(name = "shelter_phone", type = FieldType.Keyword)
    private String shelterPhone;

    // 자체 관리 필드
    @Field(name = "status", type = FieldType.Keyword)
    private String status;

    @Field(name = "api_source", type = FieldType.Keyword)
    private String apiSource;

    @Field(name = "favorite_count", type = FieldType.Integer)
    private Integer favoriteCount;

    @Field(name = "description", type = FieldType.Text)
    private String description;

    // 타임스탬프
    @Field(name = "created_at", type = FieldType.Keyword)
    private String createdAt;

    @Field(name = "updated_at", type = FieldType.Keyword)
    private String updatedAt;
}