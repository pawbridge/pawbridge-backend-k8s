package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;

/** Original controlled-ready MVC observations measured; actual registry/socket authentication remains a hosted gate. */
class F26LegacyNoteReadStreamMvcTest {
    @ParameterizedTest @MethodSource("readPaths")
    void givenMeasuredOriginalUuidPath__whenBothPutReadControllersBind__thenKeepEmptyOrMessageOnlyWireAndTheSameTransactionClockAndHintOrder(String label,String path,Measured expected)throws Exception{
        measured(label,expected);var f=new F26MvcFixture();
        try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=c.getBean(ObjectMapper.class);var before=f.read(f.sourceMvc(mapper),path,false);var after=f.read(f.candidateMvc(mapper),path,true);
            assertThat(F26MvcFixture.observation(before,f.source,f.sourceReadReached,f.sourceReadId,f.sourceClientIds)).isEqualTo(expected.values());
            assertThat(F26MvcFixture.observation(after,f.adapted,f.candidateReadReached,f.candidateReadId,f.candidateClientIds)).isEqualTo(expected.values());f.agree();
        }finally{SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @MethodSource("streamQueries")
    void givenMeasuredOriginalClientQueryAndAccept__whenBothStreamControllersBind__thenKeepHeadersAsyncOrErrorWireAndCurrentMemberOnly(String label,String clientId,String accept,Measured expected)throws Exception{
        measured(label,expected);var f=new F26MvcFixture();
        try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=c.getBean(ObjectMapper.class);var original=f.sourceMvc(mapper);var candidate=f.candidateMvc(mapper);
            var before=f.stream(original,clientId,accept,Long.toString(F26MvcFixture.EXPIRES.toEpochMilli()),false);var after=f.stream(candidate,clientId,accept,"spoofed-untrusted-header",true);
            assertThat(F26MvcFixture.observation(before,f.source,0,null,f.sourceClientIds)).isEqualTo(expected.values());
            assertThat(F26MvcFixture.observation(after,f.adapted,0,null,f.candidateClientIds)).isEqualTo(expected.values());
            assertThat(f.source.clock.calls).isZero();assertThat(f.adapted.clock.calls).isZero();assertThat(f.source.jdbc.calls).isZero();assertThat(f.adapted.jdbc.calls).isZero();f.agree();
            f.closeStream(original,before);f.closeStream(candidate,after);
        }finally{SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @ValueSource(strings={"INBOX","SENT","already-read","hint-failure"})
    void givenOwnedInboxSentOrRepeatedReadAndAnOptionalPostCommitHintFailure__whenBothMvcRead__thenKeepFirstReadInstantUpdateAttemptAndSuccessAfterCommit(String kind)throws Exception{
        canonical();var f=new F26MvcFixture();if(kind.equals("SENT"))f.setDirection("SENT");if(kind.equals("already-read"))f.setReadAt(F26MvcFixture.NOW.minusSeconds(5));f.hintFailure=kind.equals("hint-failure");
        try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=c.getBean(ObjectMapper.class);var before=f.read(f.sourceMvc(mapper),F26MvcFixture.NOTE.toString(),false);var after=f.read(f.candidateMvc(mapper),F26MvcFixture.NOTE.toString(),true);
            for(var result:List.of(before,after)){assertThat(result.getResponse().getStatus()).isEqualTo(200);assertThat(F26MvcFixture.body(result)).isEmpty();F26MvcFixture.cache(result);}
            f.agree();assertThat(f.source.phases).containsExactly("begin","insert:7","member-lock:7","current:7","clock","find","note-lock","clock","find","clock","read-update","commit","hint:7");assertThat(f.source.jdbc.updates).isOne();assertThat(f.source.clock.calls).isEqualTo(3);
            assertThat(f.source.readAt).isEqualTo(kind.equals("already-read")?F26MvcFixture.NOW.minusSeconds(5):F26MvcFixture.NOW);
        }finally{SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @ValueSource(strings={"missing","inactive","member","DAO","BEGIN"})
    void givenOwnedLookupCurrentMemberOrSqlTransactionFailure__whenBothMvcHandle__thenKeepNotFoundInactiveMemberStorageAndGenericPrecedenceAndNoHint(String kind)throws Exception{
        canonical();var f=new F26MvcFixture();if(kind.equals("missing"))f.missing();f.inactive=kind.equals("inactive");f.memberFailure=kind.equals("member");if(List.of("DAO","BEGIN").contains(kind))f.fail(kind);
        int status=kind.equals("missing")?404:kind.equals("inactive")?403:503;
        String message=switch(kind){case "missing"->"쪽지를 찾을 수 없습니다.";case "inactive"->"이용할 수 없는 회원입니다.";case "member"->"회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";case "DAO"->"쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";default->"쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";};
        try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=c.getBean(ObjectMapper.class);var before=f.read(f.sourceMvc(mapper),F26MvcFixture.NOTE.toString(),false);var after=f.read(f.candidateMvc(mapper),F26MvcFixture.NOTE.toString(),true);
            for(var result:List.of(before,after)){assertThat(result.getResponse().getStatus()).isEqualTo(status);assertThat(mapper.readTree(F26MvcFixture.body(result))).isEqualTo(mapper.valueToTree(Map.of("message",message)));F26MvcFixture.cache(result);assertThat(F26MvcFixture.body(result)).doesNotContain("private-", "SELECT", "UPDATE", "Exception");}
            f.agree();assertThat(f.source.phases).doesNotContain("hint:7","commit");assertThat(f.source.readAt).isNull();
            if(kind.equals("BEGIN")){assertThat(f.source.phases).containsExactly("begin");assertThat(f.source.clock.calls).isZero();}
            else{assertThat(f.source.phases.getLast()).isEqualTo("rollback");assertThat(f.source.phases).contains("current:7");assertThat(f.source.clock.calls).isEqualTo(kind.equals("DAO")?3:kind.equals("missing")?1:0);}
        }finally{SecurityContextHolder.clearContext();}
    }
    record Measured(Map<String,Object> values){}
    private static void measured(String label,Measured value){assertThat(value).as("UNMEASURED F26 original '%s'; fill only after complete original-only12 observations",label).isNotNull();}
    private static void canonical(){measured("canonical",(Measured)readPaths().findFirst().orElseThrow().get()[2]);}
    // Every expected binding map comes from actual original-only controlled-ready MVC observations.
    // Complete original-only controlled-ready observation12 SHA256 b872030bbd7004288e28367eaa0077c3773073f0efe552bf81ad8142295dfcc7.
    // Literal maps are independent of the candidate; real registry/JWT/socket/PG gates are separate.
    private static Measured observed(String json) {
        try {
            return new Measured(new ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Invalid measured F26 literal", failure);
        }
    }
    static Stream<Arguments> readPaths(){return Stream.of(
            Arguments.of("canonical",F26MvcFixture.NOTE.toString(),observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":null,\"buffering\":null,\"readReached\":1,\"boundId\":\"00000000-0000-0000-0000-000099800101\",\"openedClientIds\":[],\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"clock\",\"read-update\",\"commit\",\"hint:7\"],\"clockCalls\":3,\"sqlCalls\":6,\"updateCalls\":1}")),Arguments.of("uppercase","ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB",observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":null,\"buffering\":null,\"readReached\":1,\"boundId\":\"abcdefab-cdef-abcd-efab-cdefabcdefab\",\"openedClientIds\":[],\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"clock\",\"read-update\",\"commit\",\"hint:7\"],\"clockCalls\":3,\"sqlCalls\":6,\"updateCalls\":1}")),Arguments.of("short","1-1-1-1-1",observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":null,\"buffering\":null,\"readReached\":1,\"boundId\":\"00000001-0001-0001-0001-000000000001\",\"openedClientIds\":[],\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"clock\",\"read-update\",\"commit\",\"hint:7\"],\"clockCalls\":3,\"sqlCalls\":6,\"updateCalls\":1}")),
            Arguments.of("invalid","not-a-uuid",observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":\"application/json\",\"buffering\":null,\"readReached\":0,\"boundId\":null,\"openedClientIds\":[],\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")),Arguments.of("overflow","100000000-0000-0000-0000-000099800101",observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":\"application/json\",\"buffering\":null,\"readReached\":0,\"boundId\":null,\"openedClientIds\":[],\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")),Arguments.of("csv",F26MvcFixture.NOTE+","+F26MvcFixture.NOTE,observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":\"application/json\",\"buffering\":null,\"readReached\":0,\"boundId\":null,\"openedClientIds\":[],\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")));}
    static Stream<Arguments> streamQueries(){return Stream.of(
            Arguments.of("absent",null,MediaType.TEXT_EVENT_STREAM_VALUE,observed("{\"status\":200,\"body\":\"event:ready\\ndata:{}\\n\\n\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":true,\"contentType\":\"text/event-stream\",\"buffering\":\"no\",\"readReached\":0,\"boundId\":null,\"openedClientIds\":[null],\"phases\":[\"current:7\"],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")),Arguments.of("canonical",F26MvcFixture.CLIENT.toString(),MediaType.TEXT_EVENT_STREAM_VALUE,observed("{\"status\":200,\"body\":\"event:ready\\ndata:{}\\n\\n\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":true,\"contentType\":\"text/event-stream\",\"buffering\":\"no\",\"readReached\":0,\"boundId\":null,\"openedClientIds\":[\"00000000-0000-0000-0000-000099800102\"],\"phases\":[\"current:7\"],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")),Arguments.of("empty","",MediaType.TEXT_EVENT_STREAM_VALUE,observed("{\"status\":200,\"body\":\"event:ready\\ndata:{}\\n\\n\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":true,\"contentType\":\"text/event-stream\",\"buffering\":\"no\",\"readReached\":0,\"boundId\":null,\"openedClientIds\":[null],\"phases\":[\"current:7\"],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")),
            Arguments.of("invalid-sse","not-a-uuid",MediaType.TEXT_EVENT_STREAM_VALUE,observed("{\"status\":400,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":null,\"buffering\":null,\"readReached\":0,\"boundId\":null,\"openedClientIds\":[],\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")),Arguments.of("invalid-any","not-a-uuid",MediaType.ALL_VALUE,observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"async\":false,\"contentType\":\"application/json\",\"buffering\":null,\"readReached\":0,\"boundId\":null,\"openedClientIds\":[],\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"updateCalls\":0}")));}
}
