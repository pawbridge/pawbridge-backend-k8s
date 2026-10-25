package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore
import org.slf4j.LoggerFactory
import java.security.SecureRandom

class SocialNicknameGenerator(private val userRepository: UserNicknameStore) {
    private val random = SecureRandom()

    /**
     * 중복되지 않는 랜덤 닉네임 생성
     * 최대 100번 시도 후 실패 시 숫자 접미사 추가
     */
    fun generateUniqueNickname(): String {
        val maxAttempts = 100

        for (i in 0 until maxAttempts) {
            val nickname = generateRandomNickname()

            if (!userRepository.existsByNickname(nickname)) {
                log.debug("유니크 닉네임 생성 완료: attempts={}", i + 1)
                return nickname
            }
        }

        // 100번 시도 후에도 중복이면 숫자 추가
        val baseNickname = generateRandomNickname()
        val suffix = random.nextInt(10000)
        val nicknameWithSuffix = baseNickname + suffix

        log.warn("100번 재시도 후 숫자 접미사 추가")
        return nicknameWithSuffix
    }

    /**
     * 부사 + 형용사 + 동물명사 조합으로 닉네임 생성
     */
    private fun generateRandomNickname(): String {
        val adverb = ADVERBS[random.nextInt(ADVERBS.size)]
        val adjective = ADJECTIVES[random.nextInt(ADJECTIVES.size)]
        val animal = ANIMALS[random.nextInt(ANIMALS.size)]

        return adverb + adjective + animal
    }

    /**
     * 총 조합 가능한 경우의 수 반환 (테스트용)
     */
    fun getTotalCombinations(): Int = ADVERBS.size * ADJECTIVES.size * ANIMALS.size

    private companion object {
        private val log = LoggerFactory.getLogger(SocialNicknameGenerator::class.java)

        // 부사 목록 (20개)
        private val ADVERBS = listOf(
            "아주", "매우", "정말", "진짜", "엄청", "살짝", "조금",
            "완전", "너무", "굉장히", "대단히", "무척", "꽤", "제법",
            "참", "몹시", "가장", "더욱", "많이", "좀",
        )

        // 형용사 목록 (30개)
        private val ADJECTIVES = listOf(
            "귀여운", "예쁜", "멋진", "사랑스러운", "깜찍한", "똑똑한",
            "용감한", "활발한", "온순한", "순한", "착한", "영리한",
            "친근한", "다정한", "상냥한", "포근한", "따뜻한", "행복한",
            "씩씩한", "당당한", "건강한", "밝은", "명랑한", "쾌활한",
            "재빠른", "민첩한", "조용한", "차분한", "신중한", "침착한",
        )

        // 동물 명사 목록 (30개)
        private val ANIMALS = listOf(
            "강아지", "고양이", "토끼", "햄스터", "고슴도치", "페럿",
            "기니피그", "앵무새", "카나리아", "금붕어", "거북이", "도마뱀",
            "친칠라", "미어캣", "프레리독", "다람쥐", "오리", "병아리",
            "코알라", "판다", "여우", "사슴", "펭귄", "부엉이",
            "수달", "원숭이", "코끼리", "하마", "얼룩말", "사자",
        )
    }
}
