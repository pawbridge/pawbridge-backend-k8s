package com.pawbridge.backend.community.internal.post.infrastructure.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

public final class KoreanSearchTerms implements AutoCloseable {
    public static final String VERSION = "nori-9.12.3-v1";
    private final KoreanAnalyzer analyzer = new KoreanAnalyzer();

    public String document(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(term.toString());
            }
            stream.end();
        } catch (IOException failure) {
            throw new IllegalStateException("Korean search analysis failed", failure);
        }
        return String.join(" ", tokens);
    }

    @Override
    public void close() {
        analyzer.close();
    }
}
