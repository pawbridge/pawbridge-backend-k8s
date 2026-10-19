package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Four original binding observations with recording TM/JDBC; not PG/FK/SSE proof. */
class F28LegacyNoteDeletionMvcTest {
    @ParameterizedTest @MethodSource("inputs")
    void given__unmeasured_original_uuid_or_reserved_label__when__both_controllers_bind__then__require_actual_wire_metadata_and_the_same_delete_phase_order(
            String label,String path,Measured expected)throws Exception{
        measured(label,expected);var f=new F28MvcFixture();
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=context.getBean(ObjectMapper.class);
            var a=f.remove(f.sourceMvc(mapper),path,false);var b=f.remove(f.candidateMvc(mapper),path,true);
            assertThat(F28MvcFixture.observation(a,f.source,f.sourceReached,f.sourceId)).isEqualTo(expected.values());
            assertThat(F28MvcFixture.observation(b,f.adapted,f.candidateReached,f.candidateId)).isEqualTo(expected.values());f.agree();
        }finally{SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @ValueSource(strings={"SENT","hint-failure"})
    void given__sent_mailbox_or_postcommit_hint_failure__when__both_delete__then__return_empty_no_store_200_after_both_sql_stages_and_commit(String kind)throws Exception{
        canonical();var f=new F28MvcFixture();if(kind.equals("SENT"))f.direction("SENT");f.hintFailure=kind.equals("hint-failure");
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=context.getBean(ObjectMapper.class);var a=f.remove(f.sourceMvc(mapper),F28MvcFixture.NOTE.toString(),false);var b=f.remove(f.candidateMvc(mapper),F28MvcFixture.NOTE.toString(),true);
            for(var result:java.util.List.of(a,b)){assertThat(result.getResponse().getStatus()).isEqualTo(200);assertThat(F28MvcFixture.body(result)).isEmpty();F28MvcFixture.cache(result);}
            f.agree();assertThat(f.source.phases).containsExactly("begin","insert:7","member-lock:7","current:7","clock","find","note-lock","clock","find","mailbox-delete","note-delete","commit","hint:7");
            assertThat(f.source.ownMailbox).isFalse();assertThat(f.source.noteExists).isFalse();assertThat(f.source.clock.calls).isEqualTo(2);assertThat(f.source.jdbc.deletes).isEqualTo(2);
        }finally{SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @ValueSource(strings={"missing","inactive","member","DAO","BEGIN"})
    void given__owned_lookup_current_second_delete_or_begin_failure__when__both_handle__then__keep_source_error_policy_and_never_commit_or_hint(String kind)throws Exception{
        canonical();var f=new F28MvcFixture();if(kind.equals("missing"))f.missing();f.inactive=kind.equals("inactive");f.memberFailure=kind.equals("member");if(kind.equals("DAO")||kind.equals("BEGIN"))f.fail(kind);
        int status=kind.equals("missing")?404:kind.equals("inactive")?403:503;
        String message=switch(kind){case "missing"->"쪽지를 찾을 수 없습니다.";case "inactive"->"이용할 수 없는 회원입니다.";case "member"->"회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";case "DAO"->"쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";default->"쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";};
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=context.getBean(ObjectMapper.class);var a=f.remove(f.sourceMvc(mapper),F28MvcFixture.NOTE.toString(),false);var b=f.remove(f.candidateMvc(mapper),F28MvcFixture.NOTE.toString(),true);
            for(var result:java.util.List.of(a,b)){assertThat(result.getResponse().getStatus()).isEqualTo(status);assertThat(mapper.readTree(F28MvcFixture.body(result))).isEqualTo(mapper.valueToTree(Map.of("message",message)));F28MvcFixture.cache(result);assertThat(F28MvcFixture.body(result)).doesNotContain("private-","DELETE","Exception");}
            f.agree();assertThat(f.source.phases).doesNotContain("commit","hint:7");assertThat(f.source.noteExists).isTrue();assertThat(f.source.ownMailbox).isEqualTo(!kind.equals("missing"));
            if(kind.equals("BEGIN")){assertThat(f.source.phases).containsExactly("begin");assertThat(f.source.clock.calls).isZero();}
            else{assertThat(f.source.phases.getLast()).isEqualTo("rollback");assertThat(f.source.clock.calls).isEqualTo(kind.equals("DAO")?2:kind.equals("missing")?1:0);}
        }finally{SecurityContextHolder.clearContext();}
    }
    record Measured(Map<String,Object> values){}
    private static void measured(String label,Measured value){assertThat(value).as("UNMEASURED F28 original '%s'; fill only from complete original-only four observations",label).isNotNull();}
    private static void canonical(){measured("canonical",(Measured)inputs().findFirst().orElseThrow().get()[2]);}
    // Literal maps came only from complete original-only observations SHA 342a22bc5c7578ab814bfa9134ed53d8edde710659deeaadd8d04d8b55b99521.
    private static Measured observed(String json){
        try{return new Measured(new ObjectMapper().readValue(json,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("Invalid measured F28 literal",failure);}
    }
    static Stream<Arguments>inputs(){return Stream.of(
            Arguments.of("canonical",F28MvcFixture.NOTE.toString(),observed("{\"status\":200,\"body\":\"\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":null,\"async\":false,\"serviceReached\":1,\"boundId\":\"f2800000-0000-0000-0000-000000000001\",\"phases\":[\"begin\",\"insert:7\",\"member-lock:7\",\"current:7\",\"clock\",\"find\",\"note-lock\",\"clock\",\"find\",\"mailbox-delete\",\"note-delete\",\"commit\",\"hint:7\"],\"clockCalls\":2,\"sqlCalls\":7,\"deleteCalls\":2,\"ownMailboxAfter\":false,\"noteExistsAfter\":false}")),
            Arguments.of("invalid","not-a-uuid",observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":\"application/json\",\"async\":false,\"serviceReached\":0,\"boundId\":null,\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"deleteCalls\":0,\"ownMailboxAfter\":true,\"noteExistsAfter\":true}")),
            Arguments.of("blocks","blocks",observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":\"application/json\",\"async\":false,\"serviceReached\":0,\"boundId\":null,\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"deleteCalls\":0,\"ownMailboxAfter\":true,\"noteExistsAfter\":true}")),
            Arguments.of("notifications","notifications",observed("{\"status\":400,\"body\":\"{\\\"message\\\":\\\"요청 형식과 로그인 정보를 확인해 주세요.\\\"}\",\"cacheControl\":\"no-store\",\"setCookieCount\":0,\"contentType\":\"application/json\",\"async\":false,\"serviceReached\":0,\"boundId\":null,\"phases\":[],\"clockCalls\":0,\"sqlCalls\":0,\"deleteCalls\":0,\"ownMailboxAfter\":true,\"noteExistsAfter\":true}")));}
}
