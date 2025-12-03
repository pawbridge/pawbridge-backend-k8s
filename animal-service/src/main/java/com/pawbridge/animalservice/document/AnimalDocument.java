package com.pawbridge.animalservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.DateFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Elasticsearch용 동물 문서
 * - Debezium CDC를 통해 자동 동기화
 * - Animal 엔티티와 동일한 구조 유지
 * - Shelter 정보는 비정규화하여 포함
 */
@Document(indexName = "animal.animals")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalDocument {

    @Id
    private String id;  // MySQL의 id를 문자열로 저장

    // APMS 핵심 식별
    @Field(type = FieldType.Keyword)
    private String apmsDesertionNo;

    @Field(type = FieldType.Keyword)
    private String apmsNoticeNo;

    // 동물 기본 정보
    @Field(type = FieldType.Keyword)
    private String species;  // Species enum을 문자열로 저장

    @Field(type = FieldType.Text)
    private String breed;

    @Field(type = FieldType.Integer)
    private Integer birthYear;

    @Field(type = FieldType.Keyword)
    private String weight;

    @Field(type = FieldType.Text)
    private String color;

    @Field(type = FieldType.Keyword)
    private String gender;  // Gender enum을 문자열로 저장

    @Field(type = FieldType.Keyword)
    private String neuterStatus;  // NeuterStatus enum을 문자열로 저장

    @Field(type = FieldType.Text)
    private String specialMark;

    // APMS 공고 정보
    @Field(type = FieldType.Keyword)
    private String apmsProcessState;

    @Field(type = FieldType.Date)
    private LocalDate noticeStartDate;

    @Field(type = FieldType.Date)
    private LocalDate noticeEndDate;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime apmsUpdatedAt;

    // 발견 정보
    @Field(type = FieldType.Date)
    private LocalDate happenDate;

    @Field(type = FieldType.Text)
    private String happenPlace;

    // 이미지
    @Field(type = FieldType.Keyword)
    private String imageUrl;

    @Field(type = FieldType.Keyword)
    private String imageUrl2;

    // 보호소 정보 (비정규화)
    @Field(type = FieldType.Long)
    private Long shelterId;

    @Field(type = FieldType.Text)
    private String shelterName;

    @Field(type = FieldType.Text)
    private String shelterAddress;

    @Field(type = FieldType.Keyword)
    private String shelterPhone;

    // 자체 관리 필드
    @Field(type = FieldType.Keyword)
    private String status;  // AnimalStatus enum을 문자열로 저장

    @Field(type = FieldType.Keyword)
    private String apiSource;  // ApiSource enum을 문자열로 저장

    @Field(type = FieldType.Integer)
    private Integer favoriteCount;

    @Field(type = FieldType.Text)
    private String description;

    // 타임스탬프
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime createdAt;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime updatedAt;
}
