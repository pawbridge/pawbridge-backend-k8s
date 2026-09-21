package com.pawbridge.animalservice.migration;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KoreanSearchEvaluationTest {
    @Test
    void inflections_and_joined_words_retain_the_same_searchable_features() throws Exception {
        try (KoreanSearchEvaluation analyzer = new KoreanSearchEvaluation()) {
            for (String text : List.of("귀 접힘","흰털 접힌귀","겁이 많음","겁이 많고 얌전함",
                    "사람을 좋아","사람을 좋아해요","사람을 좋아함","사람을 좋아하는","사람을 좋아하고","귀여움","검정색","흰색"))
                System.out.println("NORI_EVALUATION "+text+" => "+analyzer.tokens(text));
            assertThat(analyzer.tokens("흰털 접힌귀")).containsAll(analyzer.tokens("귀 접힘"));
            assertThat(analyzer.tokens("겁이 많고 얌전함")).containsAll(analyzer.tokens("겁이 많음"));
            assertThat(analyzer.tokens("사람을 좋아해요")).containsAll(analyzer.tokens("사람을 좋아"));
            for (String text : List.of("사람을 좋아함", "사람을 좋아하는", "사람을 좋아하고"))
                assertThat(analyzer.tokens(text)).containsAll(analyzer.tokens("사람을 좋아"));
            assertThat(analyzer.tokens("귀여움")).doesNotContain("귀");
            assertThat(analyzer.tokens("포메라니안")).containsExactly("포메라니안");
        }
    }
}
