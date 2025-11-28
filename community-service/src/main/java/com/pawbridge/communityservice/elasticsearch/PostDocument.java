package com.pawbridge.communityservice.elasticsearch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * Elasticsearch Post Document
 *
 * 목적: 게시글 검색 기능 제공
 * - title, content 전문 검색
 * - nori (한국어 형태소 분석기) 사용
 */
@Document(indexName = "posts")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostDocument {

    @Id
    @Field(type = FieldType.Long)
    private Long postId;

    @Field(type = FieldType.Long)
    private Long authorId;

    // nori 분석기로 한국어 형태소 분석
    @Field(type = FieldType.Text, analyzer = "nori")
    private String title;

    @Field(type = FieldType.Text, analyzer = "nori")
    private String content;

    @Field(type = FieldType.Keyword)
    private String boardType;

    @Field(type = FieldType.Keyword)
    private List<String> imageUrls;
}
