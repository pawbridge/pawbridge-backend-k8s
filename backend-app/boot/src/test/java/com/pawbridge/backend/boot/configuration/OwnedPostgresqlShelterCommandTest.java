package com.pawbridge.backend.boot.configuration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Candidate secured Shelter writes and actual approval locks, only on the new P15 owned database. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlShelterCommandTest {
    private static final String ROOT="/api/v1/shelters";
    private static final long ADMIN=910001,OTHER_ADMIN=910007,OWN=910002,FOREIGN=910003;
    private static final SecretKey KEY=Keys.hmacShaKeyFor("owned-p15-public-synthetic-signing-key-20261021-only".getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final Map<String,String> TABLE_KEYS=Map.ofEntries(
            Map.entry("pawbridge_animal.shelters","id"),Map.entry("pawbridge_animal.animals","id"),
            Map.entry("pawbridge_animal.outbox_events","id"),Map.entry("pawbridge_animal.chatbot_sessions","id"),
            Map.entry("pawbridge_animal.animal_search_documents","animal_id"),Map.entry("pawbridge_animal.shelter_search_documents","shelter_id"),
            Map.entry("pawbridge_animal.shelter_public_information","shelter_id"),Map.entry("pawbridge_animal.shelter_daily_observations","shelter_id,observation_date"),
            Map.entry("pawbridge_user.users","user_id"),Map.entry("pawbridge_user.contact_deletions","user_id"),
            Map.entry("pawbridge_user.shelter_applications","id"));
    private Settings settings;
    private HikariDataSource pool;
    private DriverManagerDataSource probe;
    private JdbcTemplate jdbc;
    private Map<String,Fingerprint> baseline;
    private final Map<String,Long> creations=new LinkedHashMap<>();
    private final Map<Long,Map<String,Object>> applicants=new LinkedHashMap<>(),applications=new LinkedHashMap<>();
    private Map<String,Object> ownShelter;
    private boolean unresolvedWorkers;

    @BeforeAll void verify__new_owned_identity_and_exact_mutable_rows_before_application_connections() {
        observed("P15 isolated identity",()->{
            settings=Settings.load();probe=new DriverManagerDataSource(settings.jdbcUrl(),settings.username(),settings.password());
            Properties timeout=new Properties();timeout.setProperty("connectTimeout","3");timeout.setProperty("socketTimeout","5");probe.setConnectionProperties(timeout);
            verifyServer(new JdbcTemplate(probe));
            HikariConfig config=new HikariConfig();config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.username());config.setPassword(settings.password());
            config.setPoolName("owned-p15-shelter-writer");config.setMaximumPoolSize(4);config.setMinimumIdle(0);config.setConnectionTimeout(2000);config.setValidationTimeout(1000);
            config.setInitializationFailTimeout(-1);config.setReadOnly(false);config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","10");
            pool=new HikariDataSource(config);jdbc=new JdbcTemplate(pool);jdbc.setQueryTimeout(5);
            for(long id:List.of(910008L,910009L)){
                Map<String,Object> row=jdbc.queryForMap("SELECT *,updated_at::text AS audit_text FROM pawbridge_user.users WHERE user_id=?",id);
                check("ROLE_USER".equals(row.get("role"))&&row.get("care_reg_no")==null,"Only the two exact unassociated applicants may be restored");applicants.put(id,row);
            }
            for(long id:List.of(910101L,910102L)){
                Map<String,Object> row=jdbc.queryForMap("SELECT *,reviewed_at::text AS review_time_text FROM pawbridge_user.shelter_applications WHERE id=?",id);
                check(((Number)row.get("user_id")).longValue()==id-93&&"PENDING".equals(row.get("status")),"Only the exact applicant-owned pending requests may be restored");applications.put(id,row);
            }
            ownShelter=jdbc.queryForMap("SELECT *,updated_at::text AS audit_text FROM pawbridge_animal.shelters WHERE id=990010");
            check("owned-care-a".equals(ownShelter.get("care_reg_no")),"Only the pre-owned own-care shelter permits nullable-field restoration");
            baseline=fingerprint();released();
        });
    }
    @BeforeEach void require__all_eleven_original_table_fingerprints(){check(!unresolvedWorkers&&fingerprint().equals(baseline),"Every case requires the complete eleven-table baseline");}
    @AfterEach void restore__only_registered_new_shelters_and_exact_owned_review_fields(){
        observed("P15 exact owned restoration",()->{
            check(!unresolvedWorkers,"Unsettled workers forbid all restoration");released();
            for(var entry:applications.entrySet()){
                long id=entry.getKey();Map<String,Object> row=entry.getValue();
                check(id==910101||id==910102,"No foreign application may enter restoration");
                check(jdbc.update("UPDATE pawbridge_user.shelter_applications SET status=?,reviewed_by=?,reviewed_at=CAST(? AS timestamp),review_note=?,care_reg_no=? WHERE id=? AND user_id=? AND shelter_name=? AND requested_at=?",
                        row.get("status"),row.get("reviewed_by"),row.get("review_time_text"),row.get("review_note"),row.get("care_reg_no"),id,row.get("user_id"),row.get("shelter_name"),row.get("requested_at"))==1,"Owned request identity must remain unchanged");
            }
            for(var entry:applicants.entrySet()){
                long id=entry.getKey();Map<String,Object> row=entry.getValue();
                check(id==910008||id==910009,"No foreign member may enter restoration");
                check(jdbc.update("UPDATE pawbridge_user.users SET role=?,care_reg_no=?,updated_at=CAST(? AS timestamp) WHERE user_id=? AND name=? AND email=?",
                        row.get("role"),row.get("care_reg_no"),row.get("audit_text"),id,row.get("name"),row.get("email"))==1,"Owned applicant identity must remain unchanged");
            }
            check(jdbc.update("UPDATE pawbridge_animal.shelters SET phone=?,email=?,introduction=?,adoption_procedure=?,operating_hours=?,updated_at=CAST(? AS timestamp) WHERE id=990010 AND care_reg_no='owned-care-a' AND name=?",
                    ownShelter.get("phone"),ownShelter.get("email"),ownShelter.get("introduction"),ownShelter.get("adoption_procedure"),ownShelter.get("operating_hours"),ownShelter.get("audit_text"),ownShelter.get("name"))==1,"Own shelter identity must remain unchanged");
            for(var entry:creations.entrySet()){
                String care=entry.getKey();check(care.matches("p15-[0-9a-f]{32}"),"Only dispatch-registered UUID care numbers may be removed");
                List<Long> ids=jdbc.query("SELECT id FROM pawbridge_animal.shelters WHERE care_reg_no=? AND name=?",(r,n)->r.getLong(1),care,name(care));
                check(ids.size()<=1,"Owned creation identity must be unique");
                if(!ids.isEmpty()){
                    long id=ids.getFirst();check(id>990100&&(entry.getValue()==null||entry.getValue()==id),"Only the registered response identity may be cleaned");
                    check(jdbc.update("DELETE FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=? AND name=?",id,care,name(care))==1,"Owned creation cleanup must affect exactly its registered row");
                }
            }
            creations.clear();check(fingerprint().equals(baseline),"Every original row in all eleven tables must return byte-for-value to baseline");released();
        });
    }
    @AfterAll void close__only_after_all_workers_and_bindings_are_returned(){check(!unresolvedWorkers,"Unsettled workers forbid pool close");if(pool!=null){released();pool.close();check(pool.isClosed(),"The P15 pool must close");}}

    @Test void givenCurrentDatabaseRoles_whenSecuredShelterWritesRun_thenKeepWireAndRejectForeignPendingForgedOrLinkedDeletion(){
        http((mvc,context)->{
            long created=create(mvc);Map<String,Fingerprint> afterCreate=fingerprint();
            for(long id:List.of(910004L,910005L,910006L,910099L))send(mvc,"PUT",ROOT+"/"+created,token(id,"ROLE_SHELTER"),Map.of(),403);
            send(mvc,"POST",ROOT,token(OWN,"ROLE_ADMIN"),newBody(newOwnedCare()),403);
            send(mvc,"POST",ROOT,token(910004,"ROLE_ADMIN"),newBody(newOwnedCare()),403);
            send(mvc,"DELETE",ROOT+"/"+created,token(OWN,"ROLE_ADMIN"),null,403);
            send(mvc,"PUT",ROOT+"/990010",token(FOREIGN,"ROLE_SHELTER"),Map.of(),403);
            send(mvc,"PUT",ROOT+"/990010",null,Map.of(),401);
            check(fingerprint().equals(afterCreate),"Denied or forged requests must not mutate any table");
            Map<String,Object> cleared=new LinkedHashMap<>();for(String field:List.of("phone","email","introduction","adoptionProcedure","operatingHours"))cleared.put(field,null);
            JsonNode changed=send(mvc,"PUT",ROOT+"/990010",token(OWN,"ROLE_SHELTER"),cleared,200);
            for(String field:cleared.keySet())check(!changed.has(field),"Nullable update fields must retain source non-null JSON inclusion");
            check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT phone IS NULL AND email IS NULL AND introduction IS NULL AND adoption_procedure IS NULL AND operating_hours IS NULL FROM pawbridge_animal.shelters WHERE id=990010",Boolean.class)),"All five nullable fields must commit on the own shelter");
            send(mvc,"PUT",ROOT+"/"+created,token(ADMIN,"ROLE_ADMIN"),Map.of("phone","p15-phone"),200);
            send(mvc,"PUT",ROOT+"/999999",token(ADMIN,"ROLE_ADMIN"),Map.of(),500);
            send(mvc,"POST",ROOT,token(ADMIN,"ROLE_ADMIN"),Map.of("careRegNo","","name","x"),400);
            send(mvc,"PUT",ROOT+"/"+created,token(ADMIN,"ROLE_ADMIN"),Map.of("email","not-an-email"),400);
            for(long linked:List.of(990010L,990012L,990013L,990014L,990015L))send(mvc,"DELETE",ROOT+"/"+linked,token(ADMIN,"ROLE_ADMIN"),null,409);
            send(mvc,"DELETE",ROOT+"/"+created,token(ADMIN,"ROLE_ADMIN"),null,204);
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters WHERE id=?",Long.class,created)==0L,"An unlinked owned creation must be physically removed");
        });
    }

    @Test void givenOwnedShelterWithPreviousOrNullAudit_whenUpdatingThroughHttp_thenReturnFlushedAuditMatchingCommittedRow(){
        http((mvc,context)->{
            long id=create(mvc);String care=care(id);
            Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            LocalDateTime created=jdbc.queryForObject("SELECT created_at FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=?",(row,index)->row.getTimestamp(1,utc).toLocalDateTime(),id,care);
            LocalDateTime previous=jdbc.queryForObject("SELECT updated_at FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=?",(row,index)->row.getTimestamp(1,utc).toLocalDateTime(),id,care);
            for(int revision=0;revision<2;revision++){
                if(revision==1)check(jdbc.update("UPDATE pawbridge_animal.shelters SET updated_at=NULL WHERE id=? AND care_reg_no=? AND name=?",id,care,name(care))==1,
                        "Only the registered owned creation may model a legacy null audit");
                JsonNode response=send(mvc,"PUT",ROOT+"/"+id,token(ADMIN,"ROLE_ADMIN"),Map.of("phone","p15-audit-"+revision),200);
                LocalDateTime returned=JSON.treeToValue(response.path("updatedAt"),LocalDateTime.class);
                LocalDateTime committed=jdbc.queryForObject("SELECT updated_at FROM pawbridge_animal.shelters WHERE id=? AND care_reg_no=?",(row,index)->row.getTimestamp(1,utc).toLocalDateTime(),id,care);
                check(returned!=null&&returned.isAfter(previous),"The response must expose the advanced auditing value, including a legacy null value");
                check(Duration.between(returned,committed).abs().compareTo(Duration.ofNanos(1000))<0,
                        "Response and committed audit must match within PostgreSQL microsecond precision");
                check(created.equals(JSON.treeToValue(response.path("createdAt"),LocalDateTime.class)),"Updating must preserve creation audit");
                previous=committed;
            }
        });
    }

    @Test void givenClearShelterWithProjectionAndPublicInfo_whenActualDeleteFlushesInsideRollback_thenCascadeOnlyThoseRowsAndRetainObservationHistory(){
        http((mvc,context)->{
            var manager=context.getBean("animalCommandJpaTransactionManager",JpaTransactionManager.class);var tx=writer(manager);
            var commands=context.getBean(ShelterCommands.class);var em=context.getBean("animalCommandEntityManager",EntityManager.class);
            Fingerprint history=fingerprint().get("pawbridge_animal.shelter_daily_observations");
            check(count("pawbridge_animal.shelter_search_documents","shelter_id",990016)==1&&count("pawbridge_animal.shelter_public_information","shelter_id",990016)==1,"Clear fixture must exercise both real cascade rows");
            tx.executeWithoutResult(status->{
                commands.delete(ADMIN,990016L);em.flush();
                check(count("pawbridge_animal.shelters","id",990016)==0&&count("pawbridge_animal.shelter_search_documents","shelter_id",990016)==0&&count("pawbridge_animal.shelter_public_information","shelter_id",990016)==0,"Actual flush must cascade projection and public information deletion");
                check(fingerprint().get("pawbridge_animal.shelter_daily_observations").equals(history),"Directory deletion must retain the non-FK observation history");
                status.setRollbackOnly();
            });
            check(fingerprint().equals(baseline),"Rollback must restore all eleven tables after real cascade SQL");
        });
    }

    @Test void givenApprovalHoldingKeyShare_whenAnotherAdministratorDeletes_thenActualForUpdateBlocksAndCommittedMembershipReturns409(){
        http((mvc,context)->{
            long shelter=create(mvc);String registration=care(shelter);
            var memberships=context.getBean(ShelterMembership.class);var tx=new TransactionTemplate(context.getBean("writeTransactionManager",DataSourceTransactionManager.class));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            ExecutorService worker=Executors.newSingleThreadExecutor();AtomicReference<Future<HttpResult>> child=new AtomicReference<>();unresolvedWorkers=true;
            try{
                tx.executeWithoutResult(status->{
                    memberships.approve(ADMIN,910101,registration,"P15 owned approval");
                    int holder=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                    child.set(worker.submit(()->exchange(mvc,"DELETE",ROOT+"/"+shelter,token(OTHER_ADMIN,"ROLE_ADMIN"),null)));
                    int blocked=awaitBlocked(holder,"for update");check(blocked!=holder,"Deletion must use another physical PostgreSQL PID blocked by KEY SHARE");
                });
                check(child.get().get(8,TimeUnit.SECONDS).status()==409,"After approval commits, waiting deletion must observe the newly linked User and return409");
                check(count("pawbridge_animal.shelters","id",shelter)==1&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT role='ROLE_SHELTER' AND care_reg_no=? FROM pawbridge_user.users WHERE user_id=910008",Boolean.class,registration)),"Committed approval must retain both the shelter and the exact member association");
                check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT status='APPROVED' AND care_reg_no=? AND reviewed_by=910001 AND reviewed_at IS NOT NULL FROM pawbridge_user.shelter_applications WHERE id=910101 AND user_id=910008",Boolean.class,registration)),"Approval review and member association must be visible together after the writer commit");
            }finally{settle(worker);}
        });
    }

    @Test void givenDeleteHoldingNativeForUpdate_whenApprovalFinishesFullReadThenWaitsForKeyShare_thenDeletionCommitsAndApprovalRollsBackUnavailable(){
        http((mvc,context)->{
            long created=create(mvc);String care=care(created);Map<String,Fingerprint> before=fingerprint();
            var commands=context.getBean(ShelterCommands.class);var tx=writer(context.getBean("animalCommandJpaTransactionManager",JpaTransactionManager.class));
            ExecutorService worker=Executors.newSingleThreadExecutor();AtomicReference<Future<HttpResult>> child=new AtomicReference<>();unresolvedWorkers=true;
            try{
                tx.executeWithoutResult(status->{
                    // Keep delete pending until outer commit, allowing the actual independent full-detail read first.
                    commands.delete(OTHER_ADMIN,created);int holder=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
                    child.set(worker.submit(()->exchange(mvc,"POST","/api/v1/admin/users/shelter-applications/910102/approve",token(ADMIN,"ROLE_ADMIN"),Map.of("careRegNo",care,"note","P15 reverse race"))));
                    int blocked=awaitBlocked(holder,"for key share");check(blocked!=holder,"Approval must reach its actual post-detail KEY SHARE on a separate physical PostgreSQL PID");
                });
                HttpResult result=child.get().get(8,TimeUnit.SECONDS);
                check(result.status()==503,"Committed deletion must make the post-lock approval unavailable and roll back its User TX");
                check(result.body().path("message").asText().equals(ShelterMembershipRejectedException.Reason.SHELTER_UNAVAILABLE.message()),"The existing fixed shelter-unavailable response must remain the public failure");
                check(count("pawbridge_animal.shelters","id",created)==0,"The unlinked new shelter deletion must physically commit");
                Map<String,Fingerprint> after=fingerprint();
                for(String table:TABLE_KEYS.keySet())if(!table.equals("pawbridge_animal.shelters")&&!table.equals("pawbridge_animal.shelter_search_documents"))check(before.get(table).equals(after.get(table)),"Failed approval must preserve User/application and every unrelated table");
            }finally{settle(worker);}
        });
    }

    @Test void givenInheritedRepeatableReadOrReadOnly_whenShelterWriteBegins_thenRejectBeforeBusinessAndRollBackAllTables(){
        http((mvc,context)->{
            var manager=context.getBean("animalCommandJpaTransactionManager",JpaTransactionManager.class);var commands=context.getBean(ShelterCommands.class);
            for(boolean readOnly:List.of(false,true)){
                var outer=new TransactionTemplate(manager);outer.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_READ_COMMITTED:TransactionDefinition.ISOLATION_REPEATABLE_READ);outer.setReadOnly(readOnly);
                boolean rejected=false;
                try{outer.executeWithoutResult(status->commands.update(ADMIN,990010L,new ShelterCommands.Update("must-not-commit",null,null,null,null)));}
                catch(IllegalTransactionStateException expected){rejected=true;}
                check(rejected,"Actual JpaTM must reject inherited RR or RO before invoking the Shelter write callback");
                check(fingerprint().equals(baseline),"Rejected inherited transaction must preserve every original table");released();
            }
        });
    }

    @Test void givenSupplementaryTextOnOwnedCreateAndUpdate_whenShelterWritesRejectRollbackCharset_thenReturnOriginal400WithoutPrivateDatabaseLogs(){
        http((mvc,context)->{
            long created=create(mvc);Map<String,Fingerprint> before=fingerprint();
            String nonce=UUID.randomUUID().toString().replace("-","");
            String marker="OWNED-P15-PRIVATE-"+nonce;
            String address="p15-private-address-"+nonce;
            String email="p15-private-"+nonce+"@invalid.example";
            String unsupported=marker+new String(Character.toChars(0x1F600));
            Logger logger=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
            try{
                JsonNode update=send(mvc,"PUT",ROOT+"/"+created,token(ADMIN,"ROLE_ADMIN"),
                        Map.of("phone","p15-private-phone-"+nonce,"email",email,"introduction",unsupported,
                                "adoptionProcedure","p15-private-procedure","operatingHours","p15-private-hours"),400);
                check(update.path("code").asInt()==400&&update.path("message").asText().equals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
                                &&update.path("data").isNull(),
                        "Owned update must retain the original fixed rollback-charset400 envelope");
                check(fingerprint().equals(before),"Rejected supplementary update must preserve all eleven tables");released();
                String registration=newOwnedCare();Map<String,Object> body=new LinkedHashMap<>(newBody(registration));
                body.put("address",address);body.put("email",email);body.put("introduction",unsupported);
                JsonNode create=send(mvc,"POST",ROOT,token(ADMIN,"ROLE_ADMIN"),body,400);
                check(create.path("code").asInt()==400&&create.path("message").asText().equals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
                                &&create.path("data").isNull(),
                        "Owned create must retain the original fixed rollback-charset400 envelope");
                check(fingerprint().equals(before),"Rejected supplementary create must preserve all eleven tables");released();
                for(ILoggingEvent event:events.list){
                    check(!event.getLoggerName().equals("org.hibernate.engine.jdbc.spi.SqlExceptionHelper")
                                    &&event.getThrowableProxy()==null,
                            "Rollback charset rejection must not emit Hibernate SQL or throwable diagnostics");
                    String message=event.getFormattedMessage();
                    check(!message.contains("Failing row")&&!message.contains("SqlExceptionHelper"),
                            "Rollback charset rejection must not emit a failed-row diagnostic");
                    for(String privateValue:List.of(marker,"p15-address","p15@example.invalid",address,email))
                        check(!message.contains(privateValue),
                                "Rollback charset rejection must not log owned synthetic address, mail or payload markers");
                }
            }finally{logger.detachAppender(events);events.stop();}
        });
    }

    private long create(MockMvc mvc)throws Exception{
        String care=newOwnedCare();
        JsonNode response=send(mvc,"POST",ROOT,token(ADMIN,"ROLE_ADMIN"),newBody(care),201);long id=response.path("id").asLong();
        check(id>990100&&response.size()==13&&response.path("careRegNo").asText().equals(care)&&response.path("name").asText().equals(name(care))&&response.hasNonNull("createdAt")&&response.hasNonNull("updatedAt"),"Creation must return the original thirteen-field detail and pre-registered identity");creations.put(care,id);
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT s.search_dirty AND d.source_revision=s.search_revision AND d.analyzer_version IS NOT NULL FROM pawbridge_animal.shelters s JOIN pawbridge_animal.shelter_search_documents d ON d.shelter_id=s.id WHERE s.id=? AND s.care_reg_no=?",Boolean.class,id,care)),"Source row and real search projection must be independently visible after the same commit");return id;
    }
    private static Map<String,Object> newBody(String care){return Map.of("careRegNo",care,"name",name(care),"phone","p15-phone","address","p15-address","ownerName","p15-owner","organizationName","p15-org","email","p15@example.invalid","introduction","p15-intro","adoptionProcedure","p15-procedure","operatingHours","p15-hours");}
    private static String name(String care){return "OWNED-P15-"+care;}
    private String newOwnedCare(){String care="p15-"+UUID.randomUUID().toString().replace("-","");check(!creations.containsKey(care)&&jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.shelters WHERE care_reg_no=?",Long.class,care)==0L,"New care identity must be absent before dispatch");creations.put(care,null);return care;}
    private String care(long id){return creations.entrySet().stream().filter(e->e.getValue()!=null&&e.getValue()==id).map(Map.Entry::getKey).findFirst().orElseThrow(()->new AssertionError("Only a registered owned creation may enter a race"));}
    private long count(String table,String key,long id){check(TABLE_KEYS.containsKey(table),"Only the eleven selected tables may be observed");return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+key+"=?",Long.class,id);}
    private static TransactionTemplate writer(JpaTransactionManager manager){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;}
    private void settle(ExecutorService worker)throws Exception{
        worker.shutdown();boolean done=false;
        try{done=worker.awaitTermination(10,TimeUnit.SECONDS);if(!done){worker.shutdownNow();done=worker.awaitTermination(3,TimeUnit.SECONDS);}}
        catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();throw new AssertionError("Interrupted workers remain fenced from owned restoration and pool close");}
        finally{unresolvedWorkers=!done;}
        check(done,"Every child must terminate before any restoration, context close or pool close");
    }
    private int awaitBlocked(int holder,String lock){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        do{
            try(Connection control=probe.getConnection()){
                try(var clear=control.prepareStatement("SELECT pg_stat_clear_snapshot()")){clear.execute();}
                try(var query=control.prepareStatement("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid() AND state='active' AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)) AND query ILIKE '%pawbridge_animal.shelters%' AND query ILIKE ?")){
                    query.setInt(1,holder);query.setString(2,"%"+lock+"%");query.setQueryTimeout(2);
                    try(var rows=query.executeQuery()){if(rows.next()){int pid=rows.getInt(1);check(!rows.next(),"Only the controlled child may wait on the selected Shelter lock");return pid;}}
                }
                Thread.sleep(20);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("Interrupted lock observation must not become success");}
            catch(Exception failure){throw new AssertionError("P15 owned lock observation failed: "+failure.getClass().getName());}
        }while(System.nanoTime()<until);
        throw new AssertionError("The actual child SQL must reach the exact controlled PostgreSQL blocking graph");
    }
    private void http(HttpCheck action){
        observed("P15 actual secured MVC/JPA",()->{
            AtomicReference<EntityManagerFactory> factory=new AtomicReference<>();var userManager=new DataSourceTransactionManager(pool);
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,
                    ValidationAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,SpringDataWebAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class))
                    .withUserConfiguration(ShelterCommandConfiguration.class,ShelterMembershipConfiguration.class,ReadSecurityConfiguration.class)
                    .withPropertyValues("pawbridge.shelter-commands.enabled=true","pawbridge.shelter-membership.enabled=true","pawbridge.shelter-membership.animal-backend=module","spring.sql.init.mode=never","spring.jmx.enabled=false")
                    .withBean("writeDataSource",DataSource.class,()->pool,d->d.setDestroyMethodName(""))
                    .withBean("dataSource",DataSource.class,()->pool,d->d.setDestroyMethodName(""))
                    .withBean("writeJdbc",JdbcTemplate.class,()->jdbc)
                    .withBean("writeTransactionManager",DataSourceTransactionManager.class,()->userManager)
                    .withBean("transactionManager",DataSourceTransactionManager.class,()->userManager)
                    .withBean("readClock",Clock.class,Clock::systemUTC)
                    .withBean(PostTokenAuthenticator.class,()->new PostTokenAuthenticator(KEY))
                    .run(context->{
                        check(context.getStartupFailure()==null,"The real Shelter writer, full module reads, membership and security must start");factory.set(context.getBean("animalCommandEntityManagerFactory",EntityManagerFactory.class));
                        MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
                        try{action.run(mvc,context);}catch(AssertionError failure){throw failure;}catch(Exception failure){throw new AssertionError("P15 application observation failed: "+failure.getClass().getName());}
                    });
            check(!unresolvedWorkers&&factory.get()!=null&&!factory.get().isOpen(),"Only a settled P15 context may finish with its EntityManagerFactory closed");check(!pool.isClosed(),"The context must leave its externally owned pool open");released();
        });
    }
    private static String token(long id,String role){return "Bearer "+Jwts.builder().claim("userId",id).claim("role",role).expiration(new Date(System.currentTimeMillis()+300_000)).signWith(KEY).compact();}
    private static JsonNode send(MockMvc mvc,String method,String path,String authorization,Map<String,Object> body,int status)throws Exception{HttpResult result=exchange(mvc,method,path,authorization,body);check(result.status()==status,"Unexpected owned Shelter HTTP status: expected="+status+",actual="+result.status());return result.body();}
    private static HttpResult exchange(MockMvc mvc,String method,String path,String authorization,Map<String,Object> body)throws Exception{
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).servletPath(path).contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8).header("X-User-Id",ADMIN).header("X-User-Role","ROLE_ADMIN");
        if(authorization!=null)request.header("Authorization",authorization);if(body!=null)request.content(JSON.writeValueAsString(body));var response=mvc.perform(request).andReturn().getResponse();check(response.getHeader("Set-Cookie")==null,"All Shelter writes must remain stateless");
        String text=response.getContentAsString(StandardCharsets.UTF_8);return new HttpResult(response.getStatus(),text.isEmpty()?JSON.getNodeFactory().nullNode():JSON.readTree(text));
    }
    private Map<String,Fingerprint> fingerprint(){Map<String,Fingerprint> result=new LinkedHashMap<>();TABLE_KEYS.forEach((table,key)->{
        var row=jdbc.queryForMap("SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY "+key+"),'')) AS digest FROM "+table+" t");result.put(table,new Fingerprint(((Number)row.get("n")).longValue(),(String)row.get("digest")));});return result;}
    private void released(){check(!TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.hasResource(pool),"No caller TX or same-writer binding may escape a request");check(pool.getHikariPoolMXBean().getActiveConnections()==0&&pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"All four P15 connections must return after success or rollback");}
    private void verifyServer(JdbcTemplate selected){
        selected.setQueryTimeout(5);var identity=selected.queryForMap("SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner");
        check(settings.database().equals(identity.get("db"))&&settings.username().equals(identity.get("actor"))&&settings.username().equals(identity.get("session"))&&"170011".equals(identity.get("version"))&&!settings.username().equals(identity.get("owner")),"Only the exact isolated PostgreSQL17.11 database and separate writer are accepted");
        List<String> markers=selected.query("SELECT marker FROM public.pawbridge_owned_fixture",(r,n)->r.getString(1));check(markers.size()==1&&settings.marker().equals(markers.getFirst()),"Only the exact single owned marker is accepted");
        check(Boolean.TRUE.equals(selected.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",Boolean.class)),"P15 writer must have no owner or administrator inheritance");
        for(String schema:List.of("public","pawbridge_animal","pawbridge_user"))check(Boolean.FALSE.equals(selected.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')",Boolean.class,schema)),"P15 writer may not create schema objects");
        for(String table:TABLE_KEYS.keySet())check(Boolean.TRUE.equals(selected.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'TRUNCATE,REFERENCES,TRIGGER')",Boolean.class,table,table)),"Only observed tables with no administration privileges are allowed");
        for(String table:List.of("pawbridge_animal.animals","pawbridge_animal.outbox_events","pawbridge_animal.chatbot_sessions","pawbridge_animal.animal_search_documents","pawbridge_animal.shelter_public_information","pawbridge_animal.shelter_daily_observations","pawbridge_user.contact_deletions"))check(Boolean.FALSE.equals(selected.queryForObject("SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE')",Boolean.class,table)),"Only Shelter and exact User approval columns may mutate");
        check(Boolean.TRUE.equals(selected.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_animal.shelters','INSERT') AND has_table_privilege(current_user,'pawbridge_animal.shelters','UPDATE') AND has_table_privilege(current_user,'pawbridge_animal.shelters','DELETE') AND has_table_privilege(current_user,'pawbridge_animal.shelter_search_documents','INSERT') AND has_table_privilege(current_user,'pawbridge_animal.shelter_search_documents','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_animal.shelter_search_documents','DELETE') AND NOT has_table_privilege(current_user,'pawbridge_user.users','INSERT,DELETE') AND has_table_privilege(current_user,'pawbridge_user.shelter_applications','INSERT') AND has_table_privilege(current_user,'pawbridge_user.shelter_applications','UPDATE') AND NOT has_table_privilege(current_user,'pawbridge_user.shelter_applications','DELETE')",Boolean.class)),"P15 fixture must retain exact CUD and no forbidden User/search deletion privileges");
        for(String column:List.of("user_id","role","care_reg_no","updated_at"))check(Boolean.TRUE.equals(selected.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_user.users',?,'UPDATE')",Boolean.class,column)),"Only the selected User approval and row-lock columns may be writable");
        for(String column:List.of("name","email"))check(Boolean.FALSE.equals(selected.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_user.users',?,'UPDATE')",Boolean.class,column)),"User identity columns must stay read-only");
    }
    private static void observed(String phase,Checked action){try{action.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned Shelter observation failed: "+phase+"; type="+failure.getClass().getName());}}
    private static void check(boolean condition,String fixed){if(!condition)throw new AssertionError(fixed);}
    private interface Checked{void run()throws Exception;}
    private interface HttpCheck{void run(MockMvc mvc,ApplicationContext context)throws Exception;}
    private record HttpResult(int status,JsonNode body){}
    private record Fingerprint(long count,String digest){}
    private record Settings(String jdbcUrl,String database,String username,String password,String marker){
        static Settings load()throws Exception{
            String path=System.getenv("P15_PG_SETTINGS");check(path!=null&&!path.isBlank(),"P15_PG_SETTINGS is mandatory and never causes a skip");Path file=Path.of(path);
            check(file.isAbsolute()&&file.normalize().equals(file)&&file.getFileName().toString().equals("shelter-command.properties")&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file),"Only an exact absolute P15 settings file is accepted");
            check(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))&&((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1,"P15 settings must have0600 and one hard link");
            check(System.getProperty("user.name").equals(Files.getOwner(file).getName())&&Files.getOwner(file).equals(Files.getOwner(file.getParent())),"Settings and parent must have the current process owner");for(Path parent=file.getParent();parent!=null;parent=parent.getParent())check(!Files.isSymbolicLink(parent),"Settings ancestors cannot be symbolic links");
            var permissions=Files.getPosixFilePermissions(file.getParent(),LinkOption.NOFOLLOW_LINKS);check(!permissions.contains(PosixFilePermission.GROUP_WRITE)&&!permissions.contains(PosixFilePermission.OTHERS_WRITE),"P15 settings parent must reject other-user writes");
            Properties values=new Properties();try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){values.load(input);}String database=required(values,"database"),url=required(values,"jdbcUrl"),username=required(values,"username"),marker=required(values,"marker");
            check(database.matches("pb_shelter_command_owned_[0-9a-f]{12}")&&url.equals("jdbc:postgresql://127.0.0.1:52620/"+database),"Only the isolated P15 loopback database is allowed");check(username.equals("pb_shelter_command_writer")&&marker.matches("[0-9a-f]{32}"),"P15 writer and marker must be explicit");return new Settings(url,database,username,required(values,"password"),marker);
        }
        private static String required(Properties values,String key){String result=values.getProperty(key);check(result!=null&&!result.isBlank(),"Every owned setting is mandatory");return result;}
        @Override public String toString(){return "OwnedShelterSettings[redacted]";}
    }
}
