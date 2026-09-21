package com.pawbridge.animalservice.search;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.dict.UserDictionary;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

/** Versioned text analysis shared by the opt-in PostgreSQL projector and quality evaluations. */
public final class KoreanSearchAnalyzer implements AutoCloseable {
    public static final String VERSION = "nori-9.12.3-pawbridge-v2";
    private final KoreanAnalyzer analyzer;
    private final KoreanAnalyzer relationAnalyzer;

    public KoreanSearchAnalyzer() throws IOException {
        // Reuse the repository's breed/POS vocabulary. DISCARD avoids requiring both
        // a compound and its components in an AND query. This is not ES score parity.
        UserDictionary breeds = UserDictionary.open(new StringReader(String.join("\n",
                "믹스견", "시츄", "말티즈", "푸들", "치와와", "포메라니안", "요크셔테리어",
                "비글", "웰시코기", "리트리버", "진돗개", "삽살개")));
        Set<POS.Tag> stopTags = Set.of(POS.Tag.E,POS.Tag.IC,POS.Tag.J,POS.Tag.MAG,POS.Tag.MAJ,POS.Tag.MM,
                POS.Tag.SP,POS.Tag.SSC,POS.Tag.SSO,POS.Tag.SC,POS.Tag.SE,POS.Tag.XPN,POS.Tag.XSA,
                POS.Tag.XSN,POS.Tag.XSV,POS.Tag.UNA,POS.Tag.NA,POS.Tag.VSV);
        analyzer = new KoreanAnalyzer(breeds,KoreanTokenizer.DecompoundMode.DISCARD,stopTags,false);
        relationAnalyzer = new KoreanAnalyzer(breeds,KoreanTokenizer.DecompoundMode.DISCARD,Set.of(),false);
    }

    public List<String> tokens(String value) throws IOException {
        if (value == null || value.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text",value)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String token=term.toString();
                // Generic color suffix must not make all color names match each other.
                if (token.equals("색")) continue;
                // Bounded domain synonyms, not a general Korean stemmer. Nori emits
                // different lemmas for 접힘/접힌 and 좋아함/좋아해요. Apply on both sides.
                tokens.add(normalize(token));
            }
            stream.end();
        }
        return tokens;
    }

    /** Shared analysis for transactional writes and bounded document rebuilds. */
    public AnimalTerms animalTerms(String breed,String color,String mark,String description,String place) throws IOException {
        List<String> relations=new ArrayList<>(relations(mark));
        relations.addAll(relations(description));
        return new AnimalTerms(join(mark),join(breed),join(color),join(description),join(place),String.join(" ",relations));
    }

    public ShelterTerms shelterTerms(String name,String address) throws IOException {
        return new ShelterTerms(join(name),join(address));
    }

    private String join(String text) throws IOException { return String.join(" ",tokens(text)); }

    public record AnimalTerms(String mark,String breed,String color,String description,String place,String relations) {}
    public record ShelterTerms(String name,String address) {}

    /** Recognize only a simple noun + predicate query, not arbitrary natural language. */
    public Relation queryRelation(String value) throws IOException {
        List<Morpheme> core=morphemes(value).stream()
                .filter(part -> part.noun() || part.predicate()).toList();
        if (core.size()!=2 || !core.get(0).noun() || !core.get(1).predicate()) return null;
        String noun=normalize(core.get(0).term()), predicate=normalize(core.get(1).term());
        Set<String> evidence=relations(value);
        if (evidence.contains(relationKey(noun,predicate,true))) return new Relation(noun,predicate,true);
        if (evidence.contains(relationKey(noun,predicate,false))) return new Relation(noun,predicate,false);
        return null;
    }

    /** Field-local lexical evidence. Other predicates and clause boundaries close the scope.
     * This is a bounded Korean search rule, not a dependency parser or semantic classifier. */
    public Set<String> relations(String value) throws IOException {
        Set<String> result=new LinkedHashSet<>();
        PredicateScope scope=new PredicateScope(result);
        int previousEnd=0;
        for (Morpheme part:morphemes(value)) {
            if (part.start()>previousEnd && value.substring(previousEnd,part.start()).matches("(?s).*[.!?;/,\\r\\n].*"))
                scope.finish();
            previousEnd=Math.max(previousEnd,part.end());
            if (part.pos()==POS.Tag.E && Set.of("고","며","지만","는데","음","ㅁ","다","아요","어요","습니다").contains(part.term())) {
                scope.finish();
            } else if (part.noun()) {
                if (scope.predicate!=null) scope.finish();
                scope.nouns.add(normalize(part.term()));
            } else if ((part.pos()==POS.Tag.MAG && Set.of("안","못").contains(part.term()))
                    || (part.pos()==POS.Tag.VX && part.term().equals("않"))
                    || part.pos()==POS.Tag.VCN) {
                scope.negative=true;
            } else if (part.predicate()) {
                if (scope.predicate!=null) scope.finish();
                scope.predicate=normalize(part.term());
            }
        }
        scope.finish();
        return result;
    }

    private List<Morpheme> morphemes(String value) throws IOException {
        if (value==null || value.isBlank()) return List.of();
        List<Morpheme> result=new ArrayList<>();
        try (TokenStream stream=relationAnalyzer.tokenStream("text",value)) {
            CharTermAttribute term=stream.addAttribute(CharTermAttribute.class);
            PartOfSpeechAttribute pos=stream.addAttribute(PartOfSpeechAttribute.class);
            OffsetAttribute offset=stream.addAttribute(OffsetAttribute.class);
            stream.reset();
            while(stream.incrementToken()) result.add(new Morpheme(term.toString(),pos.getLeftPOS(),offset.startOffset(),offset.endOffset()));
            stream.end();
        }
        return result;
    }

    private static String normalize(String term) {
        return switch(term) { case "접힘" -> "접히"; case "좋아하" -> "좋"; default -> term; };
    }

    private static String relationKey(String noun,String predicate,boolean negative) {
        // A single safe tsvector lexeme; separators cannot collide with source words.
        return "r"+HexFormat.of().formatHex((noun+"\u0000"+predicate+"\u0000"+negative).getBytes(StandardCharsets.UTF_8));
    }

    public record Relation(String noun,String predicate,boolean negative) {
        public String key() { return relationKey(noun,predicate,negative); }
        public List<String> tokens() { return List.of(noun,predicate); }
    }

    private record Morpheme(String term,POS.Tag pos,int start,int end) {
        boolean noun() { return pos==POS.Tag.NNG || pos==POS.Tag.NNP || pos==POS.Tag.NP; }
        boolean predicate() { return pos==POS.Tag.VV || pos==POS.Tag.VA; }
    }

    private static final class PredicateScope {
        private final Set<String> result;
        private final Set<String> nouns=new LinkedHashSet<>();
        private String predicate;
        private boolean negative;
        private PredicateScope(Set<String> result) { this.result=result; }
        private void finish() {
            if (predicate!=null) for(String noun:nouns) result.add(relationKey(noun,predicate,negative));
            nouns.clear();predicate=null;negative=false;
        }
    }
    @Override public void close() { analyzer.close(); relationAnalyzer.close(); }
}
