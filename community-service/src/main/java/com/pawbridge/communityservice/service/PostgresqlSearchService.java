package com.pawbridge.communityservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.exception.SearchServiceUnavailableException;
import com.pawbridge.communityservice.search.KoreanSearchTerms;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "pawbridge.search.backend", havingValue = "postgresql")
public class PostgresqlSearchService implements SearchService {
    private final NamedParameterJdbcTemplate jdbc;
    private final KoreanSearchTerms terms;
    private final UserServiceClient users;
    private final ObjectMapper mapper;

    public PostgresqlSearchService(DataSource source, KoreanSearchTerms terms,
            UserServiceClient users, ObjectMapper mapper) {
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.terms = terms;
        this.users = users;
        this.mapper = mapper;
        this.jdbc.getJdbcTemplate().setQueryTimeout(5);
    }

    @Override
    public List<PostResponse> searchPosts(String keyword) {
        String query = terms.query(keyword);
        if (query.isEmpty()) {
            return List.of();
        }
        List<Post> posts;
        try {
            posts = jdbc.query("""
                WITH readiness AS (
                    SELECT NOT EXISTS(SELECT 1 FROM posts p LEFT JOIN post_search_documents d ON d.post_id=p.post_id
                        WHERE p.deleted_at IS NULL AND (d.post_id IS NULL OR d.title_hash<>md5(p.title)
                            OR d.content_hash<>md5(p.content) OR d.analyzer_version<>:version)) AS ready
                ), ranked AS (
                    SELECT p.*,ts_rank(d.tokens,to_tsquery('simple',:query)) AS score
                    FROM posts p JOIN post_search_documents d ON d.post_id=p.post_id
                    WHERE p.deleted_at IS NULL AND d.tokens @@ to_tsquery('simple',:query)
                    ORDER BY score DESC,p.created_at DESC,p.post_id DESC LIMIT 10
                )
                SELECT readiness.ready,ranked.* FROM readiness LEFT JOIN ranked ON readiness.ready
                ORDER BY score DESC,created_at DESC,post_id DESC
                """, Map.of("query", query, "version", KoreanSearchTerms.VERSION), (rs, row) -> {
                    if (!rs.getBoolean("ready")) {
                        throw new SearchServiceUnavailableException("검색 자료 준비 중입니다.");
                    }
                    if (rs.getObject("post_id") == null) {
                        return null;
                    }
                    List<String> images;
                    try {
                        images = rs.getString("image_urls") == null ? List.of()
                                : mapper.readValue(rs.getString("image_urls"), new TypeReference<List<String>>() {});
                    } catch (IOException failure) {
                        throw new IllegalStateException("Invalid stored post image list", failure);
                    }
                    return Post.builder()
                            .postId(rs.getLong("post_id"))
                            .authorId(rs.getLong("author_id"))
                            .title(rs.getString("title"))
                            .content(rs.getString("content"))
                            .boardType(BoardType.valueOf(rs.getString("board_type")))
                            .imageUrls(images)
                            .createdAt(rs.getObject("created_at", LocalDateTime.class))
                            .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
                            .build();
                });
        } catch (DataAccessException failure) {
            throw new SearchServiceUnavailableException();
        }
        // The JDBC connection has been returned before contacting the user service.
        return posts.stream().filter(Objects::nonNull).map(post -> {
            String nickname;
            try {
                nickname = users.getUserNickname(post.getAuthorId());
            } catch (Exception failure) {
                nickname = "사용자" + post.getAuthorId();
            }
            return PostResponse.fromEntity(post, nickname);
        }).toList();
    }
}
