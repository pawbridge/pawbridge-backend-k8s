package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.animal.internal.travel.application.PetTravel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.security.Keys;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SELECT-only owned fixture and the three public MVC reads; no collector or external API. */
@Tag("owned-postgresql") @Timeout(30)
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlAnimalTravelTest {
    private static final String ROOT="/api/v1/places";
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final Map<String,String> TABLE_KEYS=Map.of(
            "pet_travel_regions","code", "pet_travel_collection_state","id",
            "pet_travel_targets","provider,content_id", "pet_travel_places","provider,content_id",
            "pet_travel_pet_details","provider,content_id", "pet_travel_visit_details","provider,content_id",
            "pet_travel_images","provider,content_id,serial_number");
    private HikariDataSource pool;
    private JdbcTemplate fixture;
    private ObservedJdbc applicationJdbc;
    private Map<String,String> baseline;
    private final List<String> physicalIsolation=new ArrayList<>();

    @BeforeAll void givenOwnedReadonlyIdentity_whenOpeningFixture_thenRejectOtherTargetsAndPermissions() {
        observed(()->{
            Settings settings=Settings.load();
            var probe=new DriverManagerDataSource(settings.url(),settings.username(),settings.password());
            Properties timeouts=new Properties();timeouts.setProperty("connectTimeout","3");timeouts.setProperty("socketTimeout","5");probe.setConnectionProperties(timeouts);
            verifyIdentity(new JdbcTemplate(probe),settings);
            var config=new HikariConfig();config.setJdbcUrl(settings.url());config.setUsername(settings.username());config.setPassword(settings.password());
            config.setPoolName("owned-travel-reader");config.setMaximumPoolSize(1);config.setMinimumIdle(0);
            config.setConnectionTimeout(2000);config.setValidationTimeout(1000);config.setInitializationFailTimeout(-1);config.setReadOnly(true);
            config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","5");
            pool=new HikariDataSource(config);fixture=new JdbcTemplate(pool);fixture.setQueryTimeout(3);applicationJdbc=new ObservedJdbc(pool);
            check(fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_regions",Long.class)==3,"Exactly the synthetic numeric-order regions must exist");
            check(fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.pet_travel_targets WHERE provider='OTHER_PROVIDER' AND content_id='990001'",Long.class)==1,
                    "The protected foreign-provider seed must exist");
            baseline=fingerprint();released();
        });
    }
    @BeforeEach void givenPreservedSourceRows_whenStartingCase_thenRequireTheOriginalBaseline() { check(fingerprint().equals(baseline),"Every source table must match the fixture baseline");released(); }
    @AfterEach void givenSelectOnlyRequests_whenFinishingCase_thenKeepAllOwnedAndForeignRowsWithoutCleanupDml() { check(fingerprint().equals(baseline),"Travel reads must preserve all seven source tables including foreign rows");released(); }
    @AfterAll void givenCompletedRequests_whenClosing_thenCloseOnlyTheOwnedPool() { if(pool!=null){pool.close();check(pool.isClosed(),"The owned reader pool must close");} }

    @Test void givenSyntheticRegionsAndPublishedAndPreparingRows_whenReadingThreePublicRoutes_thenPreserveFilteringCountRowsAndCompleteWire() {
        http(mvc->{
            JsonNode regions=send(mvc,ROOT+"/regions",200);
            check(fields(regions).equals(Set.of("items","fetchedAt","availability"))&&"READY".equals(regions.path("availability").asText()),"Regions must use the original three-field wire");
            check(values(regions.path("items"),"code").equals(List.of("20","99","100")),"Regions must sort numerically rather than lexicographically");
            check(fields(regions.path("items").get(0)).equals(Set.of("code","name")),"Region entries must retain their two fields");
            JsonNode page=send(mvc,ROOT+"?areaCode=20&page=0",200);listObservation(true);
            check(fields(page).equals(Set.of("areaCode","items","previewOnly","fetchedAt","availability","page","size","totalElements","totalPages")),"Places must preserve the original page wire");
            check(page.path("items").size()==10&&page.path("page").asInt()==0&&page.path("size").asInt()==10
                    &&page.path("totalElements").asLong()==11&&page.path("totalPages").asLong()==2&&page.path("previewOnly").asBoolean()
                    &&"READY".equals(page.path("availability").asText()),"Count and data must apply identical provider/type/visibility/area filters");
            check(values(page.path("items"),"contentId").equals(List.of("910001","910002","910003","910004","910005","910006","910007","910008","910009","910010")),
                    "The page must use title followed by content ID without leaking excluded or foreign-provider rows");
            check(fields(page.path("items").get(0)).equals(Set.of("contentId","title","address","imageUrl"))
                    &&"https://tong.visitkorea.or.kr/cms/resource/20/MAIN.jpg".equals(page.path("items").get(0).path("imageUrl").asText()),"Place cards must retain the original four-field licensed image wire");
            JsonNode last=send(mvc,ROOT+"?areaCode=20&page=1",200);listObservation(true);
            check(values(last.path("items"),"contentId").equals(List.of("910011"))&&last.path("totalElements").asLong()==11,"A partially filled final page must retain the exact total");
            JsonNode beyond=send(mvc,ROOT+"?areaCode=20&page=2",200);listObservation(false);
            check(beyond.path("items").isArray()&&beyond.path("items").isEmpty()&&beyond.path("totalElements").asLong()==11,"Out-of-range pages must keep the count without querying rows");
            JsonNode empty=send(mvc,ROOT+"?areaCode=99",200);listObservation(false);
            check(empty.path("items").isArray()&&empty.path("items").isEmpty()&&empty.path("totalElements").asLong()==0
                    &&empty.path("totalPages").asLong()==0&&empty.path("fetchedAt").isNull()&&"PREPARING".equals(empty.path("availability").asText()),"An unfinished empty region must retain its source availability");
            JsonNode detail=send(mvc,ROOT+"/910001",200);
            check(fields(detail).equals(Set.of("place","overview","conditions","petInformationAvailable","source","fetchedAt","petInformationFetchedAt","petInformationStatus","visitInformation","images","imagesStatus","imagesFetchedAt")),"Detail must retain the original twelve-field wire");
            check("Alpha".equals(detail.path("place").path("title").asText())&&"synthetic current address".equals(detail.path("place").path("address").asText())
                    &&"synthetic overview".equals(detail.path("overview").asText())&&"DOGS".equals(detail.path("conditions").path("areas").asText())
                    &&detail.path("petInformationAvailable").asBoolean()&&"KOREA_TOURISM_ORGANIZATION".equals(detail.path("source").asText())
                    &&"READY".equals(detail.path("petInformationStatus").asText()),"Current basic fields, retained overview and valid independent pet details must merge");
            check(fields(detail.path("conditions")).size()==7&&"READY".equals(detail.path("visitInformation").path("status").asText())
                    &&detail.path("visitInformation").path("additionalItems").size()==1&&"0000-0000".equals(detail.path("visitInformation").path("informationCenter").asText()),"Visit JSONB fields and incomplete information entries must use the source mapping");
            check(detail.path("images").size()==2&&"READY".equals(detail.path("imagesStatus").asText())
                    &&values(detail.path("images"),"name").equals(List.of("first","second"))
                    &&"https://tong.visitkorea.or.kr/cms/resource/20/FIRST.jpg".equals(detail.path("images").get(0).path("originalUrl").asText()),"Gallery order, licenses, host filter and safe HTTP upgrade must survive real PostgreSQL JSONB reads");
            check("910012".equals(send(mvc,ROOT+"/910012",200).path("place").path("contentId").asText()),"Detail must not invent the list-only content-type filter");
            send(mvc,ROOT+"/999999",404);
        });
    }

    @Test void givenMinimumSelectPrivilegesAndOneReaderConnection_whenListAndErrorThenRecoveryRun_thenObservePhysicalReadOnlyRepeatableReadAndNoEscapedBinding() {
        http(mvc->{
            send(mvc,ROOT+"?areaCode=20",200);listObservation(true);
            send(mvc,ROOT+"?areaCode=777",400);check(physicalIsolation.equals(List.of("repeatable read")),"Invalid region failure must still finish its single list transaction");
            send(mvc,ROOT+"/910016",503);
            check(physicalIsolation.size()==1,"Missing required stored fields must fail inside one real readonly transaction");
            send(mvc,ROOT+"/regions",200);check(physicalIsolation.size()==1,"A reader must recover immediately after the unavailable detail");
            check(fingerprint().equals(baseline),"Neither error nor recovery may write any source or foreign row");
        });
    }

    private void listObservation(boolean rows) {
        check(physicalIsolation.equals(List.of("repeatable read")),"List count and rows require one actual PostgreSQL REPEATABLE READ transaction");
        check(applicationJdbc.calls.stream().filter("count"::equals).count()==1
                &&applicationJdbc.calls.stream().filter("rows"::equals).count()==(rows?1:0),"The actual catalog must execute count and bounded rows according to the requested page");
    }
    private void http(HttpCheck action) { observed(()->{
        var manager=new JdbcTransactionManager(pool);manager.setEnforceReadOnly(true);
        manager.addListener(new TransactionExecutionListener(){
            @Override public void afterBegin(TransactionExecution tx,Throwable failure) {
                check(failure==null&&TransactionSynchronizationManager.isActualTransactionActive()
                        &&TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&TransactionSynchronizationManager.hasResource(pool),"Every catalog operation must bind the existing reader transaction");
                check("on".equals(fixture.queryForObject("SHOW transaction_read_only",String.class))
                        &&pool.getHikariPoolMXBean().getActiveConnections()==1,"PostgreSQL must observe readonly on the single bound reader connection");
                physicalIsolation.add(fixture.queryForObject("SHOW transaction_isolation",String.class));
            }
        });
        var reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setTimeout(3);
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,ValidationAutoConfiguration.class,
                DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class))
                .withUserConfiguration(AnimalTravelConfiguration.class,ReadSecurityConfiguration.class)
                .withBean("dataSource",DataSource.class,()->pool,bean->bean.setDestroyMethodName(""))
                .withBean("readJdbc",JdbcTemplate.class,()->applicationJdbc)
                .withBean("transactionManager",JdbcTransactionManager.class,()->manager)
                .withBean("readOnlyTransaction",TransactionTemplate.class,()->reads)
                .withBean(PostTokenAuthenticator.class,()->new PostTokenAuthenticator(Keys.hmacShaKeyFor("owned-travel-public-synthetic-security-key-20261021".getBytes(StandardCharsets.UTF_8))))
                .withPropertyValues("pawbridge.animal-travel.enabled=true","pawbridge.animal-query.backend=postgresql","spring.sql.init.mode=never","spring.jmx.enabled=false")
                .run(context->{
                    check(context.getStartupFailure()==null&&context.getBeansOfType(PetTravel.class).size()==1,"The real travel configuration and secured public controller must start");
                    var list=context.getBean("animalTravelListReadOnlyTransaction",TransactionTemplate.class);
                    check(list.getTransactionManager()==manager&&list.isReadOnly()&&list.getTimeout()==3
                            &&list.getIsolationLevel()==TransactionDefinition.ISOLATION_REPEATABLE_READ,"List must change only the existing reader transaction isolation");
                    try{action.run(MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build());}
                    catch(AssertionError failure){throw failure;}catch(Exception failure){throw new AssertionError("Owned travel HTTP failed: "+failure.getClass().getName());}
                });
        check(!pool.isClosed(),"Context close must preserve the externally owned reader pool");released();
    }); }
    private JsonNode send(MockMvc mvc,String path,int expected)throws Exception {
        applicationJdbc.calls.clear();physicalIsolation.clear();released();
        var response=mvc.perform(MockMvcRequestBuilders.get(path).servletPath(path.split("\\?",2)[0])
                .header("X-User-Id","990001").header("X-User-Role","ROLE_ADMIN").header("X-Internal-Api-Key","untrusted"))
                .andReturn().getResponse();
        check(response.getStatus()==expected,"Unexpected travel HTTP status: expected="+expected+",actual="+response.getStatus());
        check(response.getHeader("Set-Cookie")==null,"Public travel reads must remain stateless");
        if(expected>=400)check("no-store".equals(response.getHeader("Cache-Control")),"Travel errors must remain noncacheable");
        JsonNode result=JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        if(expected==404)check("PET_TRAVEL_NOT_FOUND".equals(result.path("code").asText()),"Missing detail must retain the source404 code");
        if(expected==400)check("PET_TRAVEL_INVALID_REQUEST".equals(result.path("code").asText()),"Invalid filter must retain the source400 code");
        if(expected==503)check("PET_TRAVEL_UNAVAILABLE".equals(result.path("code").asText()),"Stored unavailable detail must retain the fixed503 code");
        released();return result;
    }
    private final class ObservedJdbc extends JdbcTemplate {
        final List<String> calls=new ArrayList<>();
        ObservedJdbc(DataSource source){super(source);setQueryTimeout(3);}
        private void observe(String sql){
            check(TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    &&TransactionSynchronizationManager.hasResource(pool),"Catalog SQL must run inside the bound readonly reader transaction");
            if(sql.startsWith("SELECT COUNT(*) "))calls.add("count");
            if(sql.contains(" LIMIT 10 OFFSET ?"))calls.add("rows");
        }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper){observe(sql);return super.query(sql,mapper);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){observe(sql);return super.query(sql,mapper,args);}
        @Override public <T> T queryForObject(String sql,Class<T> type,Object...args){observe(sql);return super.queryForObject(sql,type,args);}
        @Override public <T> T queryForObject(String sql,RowMapper<T> mapper){observe(sql);return super.queryForObject(sql,mapper);}
    }
    private Map<String,String> fingerprint(){Map<String,String> result=new LinkedHashMap<>();TABLE_KEYS.forEach((table,key)->result.put(table,
            fixture.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY "+key+"),'')) FROM pawbridge_animal."+table+" r",String.class)));return result;}
    private void released(){check(!TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.hasResource(pool)
            &&pool.getHikariPoolMXBean().getActiveConnections()==0&&pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"Every public operation must release its actual transaction and reader connection");}
    private static Set<String> fields(JsonNode value){Set<String> names=new HashSet<>();value.fieldNames().forEachRemaining(names::add);return names;}
    private static List<String> values(JsonNode array,String field){List<String> values=new ArrayList<>();array.forEach(row->values.add(row.path(field).asText()));return values;}
    private static void verifyIdentity(JdbcTemplate jdbc,Settings settings){
        jdbc.setQueryTimeout(3);var identity=jdbc.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        check(settings.database().equals(identity.get("db"))&&settings.username().equals(identity.get("actor"))&&settings.username().equals(identity.get("session"))
                &&"170011".equals(identity.get("version"))&&!settings.username().equals(identity.get("owner")),"Only the exact new owned reader identity is accepted");
        var markers=jdbc.query("SELECT marker FROM public.pawbridge_owned_fixture",(row,index)->row.getString(1));
        check(markers.size()==1&&settings.marker().equals(markers.getFirst()),"Only the exact owned marker is accepted");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",Boolean.class)),"Reader cannot inherit owner or administrative access");
        for(String table:TABLE_KEYS.keySet())check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,?,'INSERT,UPDATE,REFERENCES')",Boolean.class,"pawbridge_animal."+table,"pawbridge_animal."+table,"pawbridge_animal."+table)),"The seven source tables must be SELECT-only");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN ('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('public.pawbridge_owned_fixture','pawbridge_animal.pet_travel_regions','pawbridge_animal.pet_travel_collection_state','pawbridge_animal.pet_travel_targets','pawbridge_animal.pet_travel_places','pawbridge_animal.pet_travel_pet_details','pawbridge_animal.pet_travel_visit_details','pawbridge_animal.pet_travel_images')))",Long.class)==0,"Reader must have no writes or unrelated reads");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')",Long.class)==0,"Reader must have no sequence privileges");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname<>'information_schema' AND has_schema_privilege(current_user,oid,'CREATE')",Long.class)==0,"Reader must not create schema objects");
        check("on".equals(jdbc.queryForObject("SHOW transaction_read_only",String.class)),"The role must default to physical readonly before application pool creation");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void observed(Checked action){try{action.run();}catch(AssertionError failure){throw failure;}catch(Throwable failure){throw new AssertionError("Owned travel observation failed: "+failure.getClass().getName());}}
    private interface Checked{void run()throws Exception;}
    private interface HttpCheck{void run(MockMvc mvc)throws Exception;}
    private record Settings(String url,String database,String username,String password,String marker){
        static Settings load()throws Exception{
            String value=System.getenv("P14_PG_SETTINGS");check(value!=null&&!value.isBlank(),"P14_PG_SETTINGS is mandatory without skip");Path file=Path.of(value);
            check(file.isAbsolute()&&file.normalize().equals(file)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file),"Settings must be an absolute regular non-symlink file");
            check(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    &&((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1,"Settings require exact0600 and one hard link");
            check(System.getProperty("user.name").equals(Files.getOwner(file).getName())&&Files.getOwner(file).equals(Files.getOwner(file.getParent())),"Settings and parent must belong to the process owner");
            for(Path parent=file.getParent();parent!=null;parent=parent.getParent())check(!Files.isSymbolicLink(parent),"Settings ancestors must not be symlinks");
            var permissions=Files.getPosixFilePermissions(file.getParent(),LinkOption.NOFOLLOW_LINKS);check(permissions.equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)),"Settings parent must be private0700");
            var values=new Properties();try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){values.load(input);}
            check(values.stringPropertyNames().equals(Set.of("jdbcUrl","database","username","password","marker")),"Only the dedicated five settings are accepted");
            String database=required(values,"database"),url=required(values,"jdbcUrl"),username=required(values,"username"),marker=required(values,"marker");
            check(database.matches("pb_travel_owned_[0-9a-f]{12}")&&url.equals("jdbc:postgresql://127.0.0.1:52620/"+database)
                    &&username.equals("pb_travel_reader")&&marker.matches("[0-9a-f]{32}"),"Only the exact new loopback fixture and reader are accepted");
            return new Settings(url,database,username,required(values,"password"),marker);
        }
        private static String required(Properties values,String key){String value=values.getProperty(key);check(value!=null&&!value.isBlank(),"Every owned setting is required");return value;}
        @Override public String toString(){return "OwnedTravelSettings[redacted]";}
    }
}
