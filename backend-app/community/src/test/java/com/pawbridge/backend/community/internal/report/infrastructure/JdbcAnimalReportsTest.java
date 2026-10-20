package com.pawbridge.backend.community.internal.report.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.report.application.AnimalReports.*;
import com.pawbridge.backend.community.internal.post.application.*;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

class JdbcAnimalReportsTest {
    private static final Instant TIME=Instant.parse("2026-10-20T03:00:00Z");
    private static Draft draft(Kind kind,String province,String district,AnimalType type){return new Draft(kind,
            LocalDate.of(2026,10,20)," "," 서울 ",null," 개 ",null,null,null,null,null," 내용 ",province,district,type);}
    private static Details row(){return new Details(42L,7L,null,"실종 · 서울 · 개","내용",List.of("https://fixture.invalid/reports/images/old.png"),
            Kind.MISSING,LocalDate.of(2026,10,20),null,"서울",null,"개",null,null,null,null,null,
            LocalDateTime.of(2026,10,19,12,0),LocalDateTime.of(2026,10,19,12,0),"서울특별시","강남구",AnimalType.DOG);}
    private static PostUpload photo(){return new PostUpload("photo.png","image/png",8,
            ()->new ByteArrayInputStream(new byte[]{(byte)137,80,78,71,13,10,26,10}));}
    private static final class Manager extends AbstractPlatformTransactionManager {
        final List<String> events;final String name;boolean failCommit;
        Manager(List<String> e,String n){events=e;name=n;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object t,TransactionDefinition d){events.add(name+"-begin");}
        protected void doCommit(DefaultTransactionStatus s){events.add(name+"-commit");if(failCommit)throw new TransactionSystemException("private-commit");}
        protected void doRollback(DefaultTransactionStatus s){events.add(name+"-rollback");}
    }
    private static final class Jdbc extends JdbcTemplate {
        final List<String> events;List<Details> rows=new ArrayList<>(List.of(row()));List<String> sql=new ArrayList<>();List<Object[]> arguments=new ArrayList<>();
        boolean failInsert;long count=25;
        Jdbc(List<String> events){this.events=events;}
        public <T> List<T> query(String q,RowMapper<T> mapper,Object...args){sql.add(q);arguments.add(args);events.add("select");return (List<T>)rows;}
        public <T> T queryForObject(String q,Class<T> type,Object...args){sql.add(q);arguments.add(args);
            if(q.startsWith("INSERT")){events.add("insert");if(failInsert)throw new DataAccessResourceFailureException("private-db");return type.cast(43L);}
            events.add("count");return type.cast(count);}
        public int update(String q,Object...args){sql.add(q);arguments.add(args);events.add("update");return 1;}
    }
    private static final class Fixture {
        final List<String> events=new ArrayList<>();final Jdbc read=new Jdbc(events),write=new Jdbc(events);
        final Manager readManager=new Manager(events,"read"),writeManager=new Manager(events,"write");
        final PostMediaUploader uploader=mock(PostMediaUploader.class);final PostMediaRemover remover=mock(PostMediaRemover.class);
        final UserNicknameQuery users=mock(UserNicknameQuery.class);final JdbcAnimalReports reports;
        Fixture(){
            when(users.findNickname(7)).thenAnswer(c->{events.add("nickname");return Optional.of("작성자");});
            when(uploader.upload(any())).thenAnswer(c->{events.add("upload");return List.of("https://fixture.invalid/new.png");});
            reports=new JdbcAnimalReports(read,write,new TransactionTemplate(readManager),new TransactionTemplate(writeManager),users,uploader,remover,new ObjectMapper(),Clock.fixed(TIME,ZoneOffset.UTC));
        }
    }
    @Test void given__valid_report__when__creating__then__validate_upload_insert_nickname_and_commit_without_outbox(){
        var f=new Fixture();var result=f.reports.create(draft(Kind.MISSING," 서울특별시 "," 강남구 ",AnimalType.DOG),List.of(photo()),7L);
        assertThat(result.reportId()).isEqualTo(43);assertThat(result.title()).isEqualTo("실종 · 서울 · 개");assertThat(result.authorNickname()).isEqualTo("작성자");
        assertThat(result.approximateTime()).isNull();assertThat(result.province()).isEqualTo("서울특별시");assertThat(result.district()).isEqualTo("강남구");
        assertThat(f.events).containsExactly("write-begin","upload","insert","nickname","write-commit");
        assertThat(f.write.sql).hasSize(1);assertThat(f.write.sql.getFirst()).contains("INSERT INTO pawbridge_community.animal_reports","RETURNING report_id");
        assertThat(f.write.sql.getFirst().chars().filter(c->c=='?').count()).isEqualTo(f.write.arguments.getFirst().length);
        verifyNoInteractions(f.remover);
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void given__insert_or_commit_failure__when__creating__then__compensate_only_body_failure(boolean commit){
        var f=new Fixture();f.write.failInsert=!commit;f.writeManager.failCommit=commit;
        assertThatThrownBy(()->f.reports.create(draft(Kind.MISSING,null,null,null),List.of(photo()),7L)).isInstanceOf(RuntimeException.class);
        if(commit){verifyNoInteractions(f.remover);assertThat(f.events).endsWith("write-commit");}
        else{verify(f.remover).remove(List.of("https://fixture.invalid/new.png"));assertThat(f.events).endsWith("write-rollback");}
    }
    @ParameterizedTest @ValueSource(strings={"null","kind","future","region","province","district","long","empty-photo","fake-photo","six-photos"})
    void given__invalid_input__when__creating__then__reject_before_upload_or_sql(String defect){
        var f=new Fixture();Draft valid=draft(Kind.MISSING,null,null,null),request=valid;List<PostUpload> files=null;
        if(defect.equals("null"))request=null;
        if(defect.equals("kind"))request=draft(null,null,null,null);
        if(defect.equals("province"))request=draft(Kind.MISSING,"not-a-province",null,null);
        if(defect.equals("district"))request=draft(Kind.MISSING,null,"강남구",null);
        if(defect.equals("future") || defect.equals("region") || defect.equals("long"))request=new Draft(valid.kind(),defect.equals("future")?LocalDate.of(2026,10,21):valid.occurredOn(),defect.equals("long")?"x".repeat(41):null,defect.equals("region")?" ":valid.region(),null,valid.species(),null,null,null,null,null,valid.description(),null,null,null);
        if(defect.equals("empty-photo"))files=List.of(new PostUpload("x.png","image/png",0,()->new ByteArrayInputStream(new byte[0])));
        if(defect.equals("fake-photo"))files=List.of(new PostUpload("x.png","image/png",12,()->new ByteArrayInputStream(new byte[12])));
        if(defect.equals("six-photos"))files=Collections.nCopies(6,photo());
        Draft input=request;List<PostUpload> uploads=files;
        assertThatThrownBy(()->f.reports.create(input,uploads,7L)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(f.write.sql).isEmpty();verifyNoInteractions(f.uploader,f.remover,f.users);
    }
    @Test void given__invalid_body_and_missing_author__when__creating__then__body_error_precedes_login_and_valid_body_requires_login(){
        var f=new Fixture();assertThatThrownBy(()->f.reports.create(null,null,null)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(()->f.reports.create(draft(Kind.MISSING,null,null,null),null,0L)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(f.uploader,f.users,f.remover);assertThat(f.write.sql).isEmpty();
    }
    @Test void given__old_client_omitting_classification__when__updating__then__preserve_classification_images_and_audit_noop(){
        var f=new Fixture();var result=f.reports.update(42,draft(Kind.MISSING,null,null,null),null,7L);
        assertThat(result.province()).isEqualTo("서울특별시");assertThat(result.district()).isEqualTo("강남구");assertThat(result.animalType()).isEqualTo(AnimalType.DOG);
        assertThat(result.imageUrls()).isEqualTo(row().imageUrls());assertThat(result.updatedAt()).isEqualTo(row().updatedAt());
        assertThat(f.write.sql).hasSize(1);verifyNoInteractions(f.uploader,f.remover);
    }
    @Test void given__changed_fields__when__updating__then__bind_only_report_columns_keep_images_and_return_preflush_audit(){
        var f=new Fixture();var input=new Draft(Kind.MISSING,row().occurredOn(),null,"부산",null,"고양이",null,null,null,null,null,"새 내용","부산광역시",null,AnimalType.CAT);
        var result=f.reports.update(42,input,null,7L);
        assertThat(f.write.sql).hasSize(2);assertThat(f.write.sql.getLast()).contains("UPDATE pawbridge_community.animal_reports").doesNotContain("image_urls=");
        assertThat(result.region()).isEqualTo("부산");assertThat(result.district()).isNull();assertThat(result.imageUrls()).isEqualTo(row().imageUrls());
        assertThat(result.updatedAt()).isEqualTo(row().updatedAt());assertThat(f.events).endsWith("nickname","write-commit");
    }
    @ParameterizedTest @ValueSource(strings={"photos","missing","foreign","kind"})
    void given__update_rejection__when__updating__then__retain_original_failure_precedence_without_writes(String defect){
        var f=new Fixture();if(defect.equals("missing"))f.write.rows=List.of();
        Kind kind=defect.equals("kind")?Kind.SIGHTING:Kind.MISSING;Long author=defect.equals("foreign")?8L:7L;
        assertThatThrownBy(()->f.reports.update(42,draft(kind,null,null,null),defect.equals("photos")?List.of(photo()):null,author))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(defect.equals("missing")?404:defect.equals("foreign")?403:400));
        assertThat(f.write.sql).noneMatch(sql->sql.startsWith("UPDATE"));verifyNoInteractions(f.uploader,f.remover,f.users);
    }
    @Test void given__visible_owner__when__deleting__then__soft_delete_only_and_repeated_or_foreign_reject(){
        var f=new Fixture();f.reports.delete(42,7L);assertThat(f.write.sql).hasSize(2);
        assertThat(f.write.sql.getLast()).contains("SET deleted_at=?,updated_at=?").doesNotContain("DELETE FROM");verifyNoInteractions(f.remover,f.uploader,f.users);
        f.write.rows=List.of();assertThatThrownBy(()->f.reports.delete(42,7L)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
        f.write.rows=List.of(row());assertThatThrownBy(()->f.reports.delete(42,8L)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));
    }
    @Test void given__filtered_partial_page_and_unavailable_nickname__when__listing__then__literal_search_stable_order_and_independent_fallback(){
        var f=new Fixture();doAnswer(c->{f.events.add("nickname");throw new IllegalStateException("private-user");}).when(f.users).findNickname(7);
        var result=f.reports.list(new Filter(1,12,Kind.MISSING,"  DOG%_  ","서울특별시","강남구",AnimalType.DOG,LocalDate.of(2026,10,19),LocalDate.of(2026,10,20)));
        assertThat(result.total()).isEqualTo(13);assertThat(result.content().getFirst().authorNickname()).isEqualTo("사용자7");
        assertThat(f.read.sql).hasSize(1);assertThat(f.read.sql.getFirst()).contains("deleted_at IS NULL","occurred_on >= ?","occurred_on <= ?","position(? in lower(description))","ORDER BY created_at DESC, report_id DESC LIMIT ? OFFSET ?").doesNotContain("DOG%");
        assertThat(f.read.arguments.getFirst()).contains("dog%_",12,12L);assertThat(f.events).containsExactly("read-begin","select","read-commit","nickname");
    }
    @Test void given__empty_later_page__when__listing__then__count_with_identical_filters_and_missing_detail_is_404(){
        var f=new Fixture();f.read.rows=List.of();assertThat(f.reports.list(new Filter(3,12,null,null,null,null,null,null,null)).total()).isEqualTo(25);
        assertThat(f.read.sql).hasSize(2);assertThat(f.read.sql.getLast()).startsWith("SELECT count(*)").contains("deleted_at IS NULL");
        assertThatThrownBy(()->f.reports.get(42)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
    @Test void given__jpa_offset_boundary__when__listing__then__allow_int_max_but_reject_overflow_inside_transaction_before_sql_with_500(){
        var exact=new Fixture();
        exact.reports.list(new Filter(Integer.MAX_VALUE,1,null,null,null,null,null,null,null));
        assertThat(exact.read.arguments.getFirst()).containsExactly(1,(long)Integer.MAX_VALUE);
        var overflow=new Fixture();
        Throwable failure=catchThrowable(()->overflow.reports.list(new Filter(1_073_741_824,2,null,null,null,null,null,null,null)));
        assertThat(failure).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)");
        assertThat(overflow.read.sql).isEmpty();assertThat(overflow.events).containsExactly("read-begin","read-rollback");
        verifyNoInteractions(overflow.users,overflow.uploader,overflow.remover);
        var response=new com.pawbridge.backend.community.internal.report.web.AnimalReportExceptionHandler().failure((RuntimeException)failure);
        assertThat(response.getStatusCode().value()).isEqualTo(500);assertThat(response.getBody().message()).isEqualTo("서버 에러!");
    }
    @ParameterizedTest @ValueSource(strings={"page","size","keyword","province","dates","future"})
    void given__invalid_search__when__listing__then__no_read_or_user_call(String defect){
        var f=new Fixture();Filter filter=new Filter(defect.equals("page")?-1:0,defect.equals("size")?51:12,null,defect.equals("keyword")?"x".repeat(101):null,defect.equals("province")?"wrong":null,null,null,defect.equals("dates")?LocalDate.of(2026,10,20):null,defect.equals("dates")?LocalDate.of(2026,10,19):defect.equals("future")?LocalDate.of(2026,10,21):null);
        assertThatThrownBy(()->f.reports.list(filter)).isInstanceOf(ResponseStatusException.class);assertThat(f.read.sql).isEmpty();verifyNoInteractions(f.users);
    }
}
