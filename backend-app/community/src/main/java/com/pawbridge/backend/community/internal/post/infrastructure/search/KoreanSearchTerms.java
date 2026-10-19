package com.pawbridge.backend.community.internal.post.infrastructure.search;

import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

public final class KoreanSearchTerms implements AutoCloseable {
    public static final String VERSION = "nori-9.12.3-v1";
    private final KoreanAnalyzer analyzer = new KoreanAnalyzer();

    public String document(String text) {
        return String.join(" ", tokens(text));
    }

    private List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
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
        return tokens;
    }

    public String query(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() > 200 || text.indexOf(0) >= 0) {
            throw new InvalidPostSearchInputException();
        }
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(tokens(text)));
        if (terms.size() > 32) {
            throw new InvalidPostSearchInputException();
        }
        return String.join(" | ", terms.stream()
                .map(term -> "'" + term.replace("\\", "\\\\").replace("'", "''") + "'").toList());
    }

    @Override
    public void close() {
        analyzer.close();
    }
}
