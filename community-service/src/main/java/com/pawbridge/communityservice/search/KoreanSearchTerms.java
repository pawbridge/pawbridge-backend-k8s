package com.pawbridge.communityservice.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pawbridge.search.backend", havingValue="postgresql")
public final class KoreanSearchTerms {
    public static final String VERSION = "nori-9.12.3-v1";
    private final KoreanAnalyzer analyzer = new KoreanAnalyzer();

    public String document(String text) { return String.join(" ",tokens(text)); }

    public String query(String text) {
        if (text == null || text.isBlank()) return "";
        if (text.length()>200 || text.indexOf(0)>=0) throw new InvalidSearchInputException("검색어는 200자 이내여야 합니다.");
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(tokens(text)));
        if (terms.size()>32) throw new InvalidSearchInputException("검색 단어가 너무 많습니다.");
        // Match any analyzed term, as the existing ES multi_match default does.
        return String.join(" | ",terms.stream().map(term -> "'"+term.replace("\\","\\\\").replace("'","''")+"'").toList());
    }

    private List<String> tokens(String text) {
        if (text==null || text.isBlank()) return List.of();
        List<String> result=new ArrayList<>();
        try (TokenStream stream=analyzer.tokenStream("text",text)) {
            CharTermAttribute term=stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while(stream.incrementToken()) result.add(term.toString());
            stream.end();
        } catch(IOException failure) { throw new IllegalStateException("Korean search analysis failed",failure); }
        return result;
    }
    @PreDestroy public void close() { analyzer.close(); }
}
