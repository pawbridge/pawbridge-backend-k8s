package com.pawbridge.backend.compatibility;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
/** Source-selected recording comparison. Six binding rows were observed from the fixed original; missing measurements fail closed. */
class F29LegacyNoteSendMvcTest{
 @ParameterizedTest @MethodSource("inputs")
 void given__measured_send_body_or_uuid__when__both_actual_controllers_bind__then__require_the_measured_wire_and_exact_dependency_order(String label,String body,Measured expected)throws Exception{
  measured(label,expected);var f=new F29MvcFixture();try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){var m=c.getBean(ObjectMapper.class);var a=f.send(f.sourceMvc(m),body,false);var b=f.send(f.candidateMvc(m),body,true);assertThat(F29MvcFixture.observation(a,f.source,f.sourceReached)).isEqualTo(expected.values());assertThat(F29MvcFixture.observation(b,f.adapted,f.candidateReached)).isEqualTo(expected.values());f.agree();}finally{SecurityContextHolder.clearContext();}
 }
 @ParameterizedTest @ValueSource(strings={"same","conflict","gone"})
 void given__previous_send_key__when__replaying_or_conflicting__then__keep_the_original_receipt_precedence_without_clock_insert_or_push(String kind)throws Exception{
  canonical();var f=new F29MvcFixture();f.previous(kind);try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){var m=c.getBean(ObjectMapper.class);var a=f.send(f.sourceMvc(m),F29MvcFixture.CANONICAL,false);var b=f.send(f.candidateMvc(m),F29MvcFixture.CANONICAL,true);assertThat(F29MvcFixture.observation(a,f.source,f.sourceReached)).isEqualTo(F29MvcFixture.observation(b,f.adapted,f.candidateReached));for(var r:List.of(a,b)){assertThat(r.getResponse().getStatus()).isEqualTo(kind.equals("same")?200:kind.equals("gone")?410:409);F29MvcFixture.cache(r);}f.agree();assertThat(f.source.clock.calls).isZero();assertThat(f.source.jdbc.inserts).isZero();assertThat(f.source.phases).doesNotContain("blocked","clock","rate","publish:8");assertThat(f.source.phases).containsSubsequence("current:7","current:8","request");}finally{SecurityContextHolder.clearContext();}
 }
 @ParameterizedTest @ValueSource(strings={"member","DAO","BEGIN"})
 void given__typed_member_middle_insert_or_begin_failure__when__both_send__then__retain_member_storage_generic_503_without_commit_or_notification(String kind)throws Exception{
  canonical();var f=new F29MvcFixture();f.memberFailure=kind.equals("member");if(!f.memberFailure)f.fail(kind);try(var c=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){var m=c.getBean(ObjectMapper.class);var a=f.send(f.sourceMvc(m),F29MvcFixture.CANONICAL,false);var b=f.send(f.candidateMvc(m),F29MvcFixture.CANONICAL,true);assertThat(F29MvcFixture.observation(a,f.source,f.sourceReached)).isEqualTo(F29MvcFixture.observation(b,f.adapted,f.candidateReached));for(var r:List.of(a,b)){assertThat(r.getResponse().getStatus()).isEqualTo(503);F29MvcFixture.cache(r);String message=switch(kind){case "member"->"회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";case "DAO"->"쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";default->"쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";};assertThat(m.readTree(F29MvcFixture.body(r))).isEqualTo(m.valueToTree(Map.of("message",message)));assertThat(F29MvcFixture.body(r)).doesNotContain("private-","INSERT","Exception");}f.agree();assertThat(f.source.phases).doesNotContain("commit","publish:8");assertThat(f.source.noteExists).isFalse();assertThat(f.source.clock.calls).isEqualTo(kind.equals("DAO")?1:0);}finally{SecurityContextHolder.clearContext();}
 }
 record Measured(Map<String,Object>values){}
 private static void measured(String label,Measured v){assertThat(v).as("UNMEASURED original F29 %s; fill only from complete six-row original JSON",label).isNotNull();}
 private static void canonical(){measured("canonical",(Measured)inputs().findFirst().orElseThrow().get()[2]);}
 // Measured original six-row JSON SHA256 49d599ad7c82b44cece859f71d3441b751d2d8bfe057229180c099ddb4f01069; selected MVC/RecordingTM, not PG/JWT/SSE.
 static Measured observed(Object...pairs){var values=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return new Measured(values);}
 static Stream<Arguments>inputs(){return Stream.of(
   Arguments.of("canonical",F29MvcFixture.CANONICAL,observed("status",200,"body","{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"noteId\":\"f2900000-0000-0000-0000-000000000003\"}}","cacheControl","no-store","setCookieCount",0,"contentType","application/json","async",false,"serviceReached",1,"phases",List.of("begin","insert:7","member-lock:7","insert:8","member-lock:8","current:7","current:8","request","blocked","clock","rate","rate","note-insert","mailbox-insert","request-insert","commit","publish:8"),"clockCalls",1,"sqlCalls",11,"insertCalls",3,"committed",true)),
   Arguments.of("absent",null,observed("status",400,"body","{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}","cacheControl","no-store","setCookieCount",0,"contentType","application/json","async",false,"serviceReached",0,"phases",List.of(),"clockCalls",0,"sqlCalls",0,"insertCalls",0,"committed",false)),
   Arguments.of("literal-null","null",observed("status",400,"body","{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}","cacheControl","no-store","setCookieCount",0,"contentType","application/json","async",false,"serviceReached",0,"phases",List.of(),"clockCalls",0,"sqlCalls",0,"insertCalls",0,"committed",false)),
   Arguments.of("invalid-json","{",observed("status",400,"body","{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}","cacheControl","no-store","setCookieCount",0,"contentType","application/json","async",false,"serviceReached",0,"phases",List.of(),"clockCalls",0,"sqlCalls",0,"insertCalls",0,"committed",false)),
   Arguments.of("invalid-request-uuid",F29MvcFixture.CANONICAL.replace(F29MvcFixture.KEY.toString(),"not-a-uuid"),observed("status",400,"body","{\"message\":\"요청 형식과 로그인 정보를 확인해 주세요.\"}","cacheControl","no-store","setCookieCount",0,"contentType","application/json","async",false,"serviceReached",0,"phases",List.of(),"clockCalls",0,"sqlCalls",0,"insertCalls",0,"committed",false)),
   Arguments.of("missing-request-id","{\"recipientId\":8,\"body\":\"private\"}",observed("status",400,"body","{\"message\":\"받는 사람과 쪽지 내용을 확인해 주세요.\"}","cacheControl","no-store","setCookieCount",0,"contentType","application/json","async",false,"serviceReached",1,"phases",List.of(),"clockCalls",0,"sqlCalls",0,"insertCalls",0,"committed",false))
 );}
}
