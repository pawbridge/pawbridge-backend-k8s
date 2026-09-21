package com.pawbridge.animalservice.migration;

import com.pawbridge.animalservice.search.KoreanSearchAnalyzer;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Evaluation uses the production analyzer to prevent quality/runtime drift. */
final class KoreanSearchEvaluation implements AutoCloseable {
    private final KoreanSearchAnalyzer delegate = new KoreanSearchAnalyzer();
    KoreanSearchEvaluation() throws IOException { }
    List<String> tokens(String value) throws IOException { return delegate.tokens(value); }
    Set<String> relations(String value) throws IOException { return delegate.relations(value); }
    KoreanSearchAnalyzer.Relation queryRelation(String value) throws IOException { return delegate.queryRelation(value); }
    @Override public void close() { delegate.close(); }
}
