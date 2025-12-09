package com.pawbridge.userservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "users",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"email", "provider"}),
        @UniqueConstraint(columnNames = {"nickname"})
    }
)
// Auditing 기능을 활성화
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(nullable = false, unique = true, length = 30)
    private String nickname;

    @Column(nullable = true)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /**
     * 보호소 등록번호 (careRegNo)
     * - ROLE_SHELTER 회원만 가짐
     * - 보호소 소속을 확인하기 위한 필드
     * - 예: "348527200900001"
     */
    @Column(length = 50)
    private String careRegNo;

    @Column(length = 20, nullable = false)
    @Builder.Default
    private String provider = "LOCAL";

    @Column(length = 100)
    private String providerId;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /**
     * 일반 회원가입 사용자 생성 (LOCAL)
     */
    public static User createLocalUser(String email, String name, String password, String nickname, Role role, String careRegNo) {
        return User.builder()
                .email(email)
                .name(name)
                .nickname(nickname)
                .password(password)
                .provider("LOCAL")
                .role(role)
                .careRegNo(careRegNo)
                .build();
    }

    /**
     * 소셜 로그인 사용자 생성 (GOOGLE 등)
     */
    public static User createSocialUser(String email, String name, String provider, String providerId, String nickname) {
        return User.builder()
                .email(email)
                .name(name)
                .nickname(nickname)
                .password("OAuth2")  // OAuth2 사용자는 비밀번호 불필요, 더미 값 설정
                .provider(provider)
                .providerId(providerId)
                .role(Role.ROLE_USER)
                .build();
    }

    /**
     * 닉네임 변경
     */
    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 비밀번호 변경
     */
    public void updatePassword(String newPassword) {
        this.password = newPassword;
    }

    /**
     * LOCAL 사용자인지 확인
     */
    public boolean isLocalUser() {
        return "LOCAL".equals(this.provider);
    }
}
