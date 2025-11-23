package com.pawbridge.userservice.service;

import com.pawbridge.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NicknameGeneratorService {

    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    // 부사 목록 (20개)
    private static final List<String> ADVERBS = Arrays.asList(
            "아주", "매우", "정말", "진짜", "엄청", "살짝", "조금",
            "완전", "너무", "굉장히", "대단히", "무척", "꽤", "제법",
            "참", "몹시", "가장", "더욱", "많이", "좀"
    );

    // 형용사 목록 (30개)
    private static final List<String> ADJECTIVES = Arrays.asList(
            "귀여운", "예쁜", "멋진", "사랑스러운", "깜찍한", "똑똑한",
            "용감한", "활발한", "온순한", "순한", "착한", "영리한",
            "친근한", "다정한", "상냥한", "포근한", "따뜻한", "행복한",
            "씩씩한", "당당한", "건강한", "밝은", "명랑한", "쾌활한",
            "재빠른", "민첩한", "조용한", "차분한", "신중한", "침착한"
    );

    // 동물 명사 목록 (30개)
    private static final List<String> ANIMALS = Arrays.asList(
            "강아지", "고양이", "토끼", "햄스터", "고슴도치", "페럿",
            "기니피그", "앵무새", "카나리아", "금붕어", "거북이", "도마뱀",
            "친칠라", "미어캣", "프레리독", "다람쥐", "오리", "병아리",
            "코알라", "판다", "여우", "사슴", "펭귄", "부엉이",
            "수달", "원숭이", "코끼리", "하마", "얼룩말", "사자"
    );

    /**
     * 중복되지 않는 랜덤 닉네임 생성
     * 최대 100번 시도 후 실패 시 숫자 접미사 추가
     */
    public String generateUniqueNickname() {
        int maxAttempts = 100;

        for (int i = 0; i < maxAttempts; i++) {
            String nickname = generateRandomNickname();

            if (!userRepository.existsByNickname(nickname)) {
                log.debug("유니크 닉네임 생성 완료: {} ({}번째 시도)", nickname, i + 1);
                return nickname;
            }
        }

        // 100번 시도 후에도 중복이면 숫자 추가
        String baseNickname = generateRandomNickname();
        int suffix = random.nextInt(10000);
        String nicknameWithSuffix = baseNickname + suffix;

        log.warn("100번 재시도 후 숫자 접미사 추가: {}", nicknameWithSuffix);
        return nicknameWithSuffix;
    }

    /**
     * 부사 + 형용사 + 동물명사 조합으로 닉네임 생성
     */
    private String generateRandomNickname() {
        String adverb = ADVERBS.get(random.nextInt(ADVERBS.size()));
        String adjective = ADJECTIVES.get(random.nextInt(ADJECTIVES.size()));
        String animal = ANIMALS.get(random.nextInt(ANIMALS.size()));

        return adverb + adjective + animal;
    }

    /**
     * 총 조합 가능한 경우의 수 반환 (테스트용)
     */
    public int getTotalCombinations() {
        return ADVERBS.size() * ADJECTIVES.size() * ANIMALS.size();
    }
}
