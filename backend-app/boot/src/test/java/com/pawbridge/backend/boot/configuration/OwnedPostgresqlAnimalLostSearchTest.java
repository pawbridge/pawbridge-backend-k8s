package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Candidate-only read-only PG/MVC/local-Python transport observations, never actual AI or production. */
@Tag("owned-postgresql") @Timeout(20)
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class OwnedPostgresqlAnimalLostSearchTest {
    private static final String ROUTE="/api/v1/animals/lost-candidates";
    private static final String KEY="p10-public-synthetic-internal-key";
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final long DOG_A=990110,DOG_B=990111,CAT=990112,CAT_ADOPTED=990113,DOG_ADOPTED=990114,ENDED=990115,MISSING=990999;
    private HikariDataSource pool;
    private ObservedSource applicationSource;
    private JdbcTemplate fixture;
    private Map<String,String> baseline;

    @BeforeAll void openOnlyTheVerifiedReadonlyFixture() {
        observed(()->{
            Settings settings=Settings.load();
            var config=new HikariConfig();config.setJdbcUrl(settings.url());config.setUsername(settings.username());config.setPassword(settings.password());
            config.setPoolName("owned-lost-search-reader");config.setMaximumPoolSize(1);config.setMinimumIdle(0);
            config.setConnectionTimeout(2000);config.setValidationTimeout(1000);config.setInitializationFailTimeout(-1);config.setReadOnly(true);
            config.addDataSourceProperty("connectTimeout","3");config.addDataSourceProperty("socketTimeout","5");
            pool=new HikariDataSource(config);fixture=new JdbcTemplate(pool);fixture.setQueryTimeout(5);
            verifyIdentity(settings);applicationSource=new ObservedSource(pool);baseline=fingerprint();
            check(fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE id IN (990110,990111,990112,990113,990114,990115)",Long.class)==6,
                    "All Root-owned filter seeds must exist");
            check(fixture.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE id=990999",Long.class)==0,"The missing candidate must be absent");
            released();
        });
    }
    @BeforeEach void requireBaseline(){check(fingerprint().equals(baseline),"The readonly baseline must be unchanged before each case");released();}
    @AfterEach void preserveAllRootOwnedAndForeignRows(){check(fingerprint().equals(baseline),"All Animal/Shelter rows must remain unchanged");released();}
    @AfterAll void closeOnlyTheOwnedPool(){if(pool!=null){pool.close();check(pool.isClosed(),"The owned reader pool must close");}}

    @Test void givenCurrentDogsAndStaleRemoteCandidates_whenUploadingPublicly_thenKeepRemoteOrderFirstEvidenceAndCurrentShelterData(){
        http((mvc,stub,begins)->{
            stub.reply.set(reply(200,List.of(candidate(DOG_B,"DISCOVERY_PLACE_TEXT_MATCH"),candidate(MISSING),candidate(CAT),
                    candidate(DOG_ADOPTED),candidate(ENDED),candidate(DOG_A,"FOUND_ON_OR_AFTER_LOST_DATE"),
                    candidate(DOG_B,"REGISTERED_DESCRIPTION_TEXT_MATCH"))));
            int before=begins.get();JsonNode body=send(mvc,stub,"DOG",false,200).body();
            check(ids(body).equals(List.of(DOG_B,DOG_A)),"Current DB existence, species and active state must filter without reordering");
            check(body.path("candidates").get(0).path("matchedEvidence").equals(JSON.valueToTree(List.of("DISCOVERY_PLACE_TEXT_MATCH"))),
                    "Duplicate IDs must retain the first remote evidence");
            for(JsonNode value:body.path("candidates")){
                JsonNode animal=value.path("animal");Map<String,Object> row=fixture.queryForMap("SELECT breed,status,species,shelter_id FROM pawbridge_animal.animals WHERE id=?",animal.path("id").asLong());
                check(animal.path("species").asText().equals(row.get("species"))&&animal.path("status").asText().equals(row.get("status"))
                        &&animal.path("shelterId").asLong()==((Number)row.get("shelter_id")).longValue()
                        &&"synthetic shelter A".equals(animal.path("shelterName").asText())&&"02-000-0000".equals(value.path("shelterPhone").asText())
                        &&!value.has("imageScore"),"Public candidates must use current Animal/Shelter values and omit internal scores");
            }
            check(begins.get()==before+1,"One short reader transaction must begin only after inference");
        });
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void givenCurrentCatAndResolvedRows_whenSelectingTheResolvedOption_thenFilterUsingCurrentDatabaseState(boolean includeResolved){
        http((mvc,stub,begins)->{
            stub.reply.set(reply(200,List.of(candidate(CAT_ADOPTED),candidate(DOG_A),candidate(CAT),candidate(MISSING))));
            JsonNode body=send(mvc,stub,"CAT",includeResolved,200).body();
            check(ids(body).equals(includeResolved?List.of(CAT_ADOPTED,CAT):List.of(CAT)),"Only the selected species and explicitly allowed resolved states may survive");
        });
    }

    @Test void givenRemote503_whenRetryingTheSameService_thenReturnRetryAfterWithoutDatabaseWorkAndRecoverThePermit(){
        http((mvc,stub,begins)->{
            stub.reply.set(new Reply(503,"{}".getBytes(StandardCharsets.UTF_8)));
            int before=begins.get(),borrowed=applicationSource.borrowed.get();var failure=send(mvc,stub,"DOG",false,503);
            check("3".equals(failure.result().getResponse().getHeader("Retry-After"))&&begins.get()==before&&applicationSource.borrowed.get()==borrowed,
                    "Remote failure must return Retry-After and perform no database work");
            stub.reply.set(reply(200,List.of(candidate(DOG_A))));
            check(ids(send(mvc,stub,"DOG",false,200).body()).equals(List.of(DOG_A)),"The same instance must release its inference permit after failure");
        });
    }

    @Test void givenMoreThanTwentyRemoteCandidates_whenUploading_thenRejectBeforeOpeningAnyReaderTransaction(){
        http((mvc,stub,begins)->{
            List<Map<String,Object>> candidates=new ArrayList<>();for(int i=0;i<21;i++)candidates.add(candidate(DOG_A));
            stub.reply.set(reply(200,candidates));int before=begins.get(),borrowed=applicationSource.borrowed.get();
            var response=send(mvc,stub,"DOG",false,503);
            check("3".equals(response.result().getResponse().getHeader("Retry-After"))&&begins.get()==before&&applicationSource.borrowed.get()==borrowed,
                    "Oversized remote candidate lists must be rejected with no DB access");
        });
    }

    private void http(HttpCheck action){observed(()->{
        try(var stub=new PythonStub()){
            AtomicReference<EntityManagerFactory> emf=new AtomicReference<>();AtomicInteger begins=new AtomicInteger();
            stub.begins=begins;
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                    JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,ValidationAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class))
                    .withUserConfiguration(AnimalLostSearchConfiguration.class,ReadSecurityConfiguration.class)
                    .withBean("dataSource",DataSource.class,()->applicationSource)
                    .withBean(PostTokenAuthenticator.class,()->new PostTokenAuthenticator(LostSearchTestSecurity.KEY))
                    .withPropertyValues("pawbridge.animal-lost-search.enabled=true","lost-search.python-url="+stub.url(),
                            "python-ai-service.internal-api-key="+KEY,"spring.sql.init.mode=never","spring.jmx.enabled=false")
                    .run(context->{
                        check(context.getStartupFailure()==null,"The actual reader, multipart controller and security chain must start");
                        emf.set(context.getBean("animalLostSearchEntityManagerFactory",EntityManagerFactory.class));
                        var manager=context.getBean("animalLostSearchTransactions",JpaTransactionManager.class);
                        manager.addListener(new TransactionExecutionListener(){
                            @Override public void beforeBegin(TransactionExecution transaction){begins.incrementAndGet();}
                            @Override public void afterBegin(TransactionExecution transaction,Throwable failure){
                                check(failure==null&&TransactionSynchronizationManager.isActualTransactionActive()
                                        &&TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&TransactionSynchronizationManager.hasResource(applicationSource),
                                        "The actual post-inference transaction must bind a read-only reader connection");
                                var bound=new JdbcTemplate(applicationSource);
                                check("on".equals(bound.queryForObject("SHOW transaction_read_only",String.class))
                                        &&pool.getHikariPoolMXBean().getActiveConnections()==1,"PostgreSQL must observe read-only work on the single pooled connection");
                            }
                        });
                        MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
                        try{action.run(mvc,stub,begins);}catch(AssertionError fail){throw fail;}catch(Exception fail){throw new AssertionError("Owned photo request failed");}
                    });
            check(emf.get()!=null&&!emf.get().isOpen(),"Each request context must close its owned reader factory");
            stub.healthy();check(!pool.isClosed(),"Context shutdown must not close the externally owned fixture pool");released();
        }
    });}

    private Response send(MockMvc mvc,PythonStub stub,String species,boolean resolved,int expected)throws Exception{
        released();stub.expectedBegins=stub.begins.get();stub.expectedBorrowed=applicationSource.borrowed.get();
        var response=mvc.perform(multipart(ROUTE).file(new MockMultipartFile("image","private-original.jpg","image/jpeg",new byte[]{1,2,3,4}))
                .param("species",species).param("lostDate","2026-10-20").param("region","  synthetic region  ")
                .param("description","  synthetic description  ").param("includeAdoptedOrReturned",Boolean.toString(resolved))
                .header("X-Internal-Api-Key","untrusted-client-key").header("X-User-Id","99")).andReturn();
        check(response.getResponse().getStatus()==expected,"The public multipart route must preserve its expected status");
        check(response.getResponse().getHeader("Set-Cookie")==null,"Public inference must not create an authenticated session");
        stub.healthy();released();String body=response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Response(response,body.isBlank()?JSON.getNodeFactory().nullNode():JSON.readTree(body));
    }
    private static List<Long> ids(JsonNode response){List<Long> values=new ArrayList<>();response.path("candidates").forEach(c->values.add(c.path("animal").path("id").asLong()));return values;}
    private static Map<String,Object> candidate(long id,String...evidence){return Map.of("animalId",id,"imageScore",0.7,"matchedEvidence",List.of(evidence));}
    private static Reply reply(int status,List<Map<String,Object>> candidates)throws Exception{return new Reply(status,JSON.writeValueAsBytes(Map.of("candidates",candidates)));}
    private record Reply(int status,byte[] body){}
    private record Response(MvcResult result,JsonNode body){}

    private final class PythonStub implements AutoCloseable{
        final HttpServer server;final ExecutorService executor;
        final AtomicReference<Reply> reply=new AtomicReference<>();final AtomicReference<AssertionError> failure=new AtomicReference<>();
        AtomicInteger begins;volatile int expectedBegins,expectedBorrowed;
        PythonStub()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);
            try{
            server.createContext("/internal/animals/lost-candidates",exchange->{
                byte[] bytes="{}".getBytes(StandardCharsets.UTF_8);int status=500;
                try{
                    check("POST".equals(exchange.getRequestMethod())&&"/internal/animals/lost-candidates".equals(exchange.getRequestURI().getPath())
                            &&exchange.getRequestURI().getRawQuery()==null&&KEY.equals(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")),"The local Python request must use the configured key and exact POST");
                    check(pool.getHikariPoolMXBean().getActiveConnections()==0&&begins.get()==expectedBegins&&applicationSource.borrowed.get()==expectedBorrowed,
                            "Remote inference must precede every reader TX begin and connection acquisition");
                    byte[] request=exchange.getRequestBody().readNBytes(8193);check(request.length<=8192,"The synthetic multipart request must remain bounded");
                    String body=new String(request,StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                    check(body.contains("filename=\"photo\"")&&body.contains("application/octet-stream")&&!body.contains("private-original.jpg"),"The native multipart client must use a fixed outbound filename and type");
                    Reply value=reply.get();check(value!=null,"A synthetic Python reply must be selected");status=value.status();bytes=value.body();
                }catch(Throwable unexpected){failure.compareAndSet(null,new AssertionError("Owned Python boundary observation failed"));}
                try{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
                finally{exchange.close();}
            });server.start();
            }catch(Throwable startFailure){server.stop(0);executor.shutdownNow();throw startFailure;}
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void healthy(){check(failure.get()==null,"The local Python boundary assertions must all pass");}
        @Override public void close()throws Exception{try{server.stop(0);}finally{executor.shutdownNow();check(executor.awaitTermination(2,TimeUnit.SECONDS),"The local Python executor must terminate");}}
    }
    private static final class ObservedSource extends DelegatingDataSource{
        final AtomicInteger borrowed=new AtomicInteger();ObservedSource(DataSource delegate){super(delegate);}
        @Override public Connection getConnection()throws SQLException{borrowed.incrementAndGet();return super.getConnection();}
        @Override public Connection getConnection(String user,String password)throws SQLException{borrowed.incrementAndGet();return super.getConnection(user,password);}
    }
    private Map<String,String> fingerprint(){Map<String,String> result=new LinkedHashMap<>();for(String table:List.of("animals","shelters"))result.put(table,fixture.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY id),'')) FROM pawbridge_animal."+table+" r",String.class));return result;}
    private void released(){check(!TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.hasResource(applicationSource)
            &&pool.getHikariPoolMXBean().getActiveConnections()==0&&pool.getHikariPoolMXBean().getThreadsAwaitingConnection()==0,"The owned reader must return every transaction and connection");}
    private void verifyIdentity(Settings s){
        Map<String,Object> identity=fixture.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        check(s.database().equals(identity.get("db"))&&s.username().equals(identity.get("actor"))&&s.username().equals(identity.get("session"))
                &&"170011".equals(identity.get("version"))&&!s.username().equals(identity.get("owner")),"Only the new owned PostgreSQL reader is allowed");
        List<String> markers=fixture.query("SELECT marker FROM public.pawbridge_owned_fixture",(row,index)->row.getString(1));
        check(markers.size()==1&&s.marker().equals(markers.getFirst()),"The exact owned fixture marker must match");
        check(Boolean.TRUE.equals(fixture.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user",Boolean.class)),"The reader must not have administrative or owner membership");
        for(String table:List.of("animals","shelters"))check(Boolean.TRUE.equals(fixture.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,?,'INSERT,UPDATE,REFERENCES')",Boolean.class,"pawbridge_animal."+table,"pawbridge_animal."+table,"pawbridge_animal."+table)),"Fixture business tables must be SELECT-only");
        check(fixture.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN ('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('pawbridge_animal.animals','pawbridge_animal.shelters','public.pawbridge_owned_fixture')))",Long.class)==0,"The reader must have no writes or unrelated table reads");
        check(fixture.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')",Long.class)==0,"The reader must have no sequence privileges");
        for(String schema:List.of("public","pawbridge_animal","pawbridge_user"))check(Boolean.FALSE.equals(fixture.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')",Boolean.class,schema)),"The reader must not create schema objects");
        check("on".equals(fixture.queryForObject("SHOW transaction_read_only",String.class)),"The dedicated role must default to read-only");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void observed(Checked work){try{work.run();}catch(AssertionError fail){throw fail;}catch(Throwable fail){throw new AssertionError("Owned lost-search observation failed");}}
    private interface Checked{void run()throws Exception;}
    private interface HttpCheck{void run(MockMvc mvc,PythonStub stub,AtomicInteger begins)throws Exception;}
    private record Settings(String url,String database,String username,String password,String marker){
        static Settings load()throws Exception{
            String value=System.getenv("P10_PG_SETTINGS");check(value!=null&&!value.isBlank(),"P10_PG_SETTINGS is required without skip");Path p=Path.of(value);
            check(p.isAbsolute()&&p.normalize().equals(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p),"Settings must be an absolute regular non-link file");
            check(Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    &&((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()==1,"Settings require 0600 and one hard link");
            check(System.getProperty("user.name").equals(Files.getOwner(p).getName())&&Files.getOwner(p).equals(Files.getOwner(p.getParent())),"Settings and parent must belong to this user");
            for(Path parent=p.getParent();parent!=null;parent=parent.getParent())check(!Files.isSymbolicLink(parent),"Settings ancestors must not be links");
            Set<PosixFilePermission> perms=Files.getPosixFilePermissions(p.getParent());check(!perms.contains(PosixFilePermission.GROUP_WRITE)&&!perms.contains(PosixFilePermission.OTHERS_WRITE),"Settings parent must reject other writers");
            Properties props=new Properties();try(InputStream input=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){props.load(input);}
            check(props.stringPropertyNames().equals(Set.of("jdbcUrl","database","username","password","marker")),"Only the five dedicated reader settings are allowed");
            String db=required(props,"database"),url=required(props,"jdbcUrl"),user=required(props,"username"),marker=required(props,"marker");
            check(db.equals("pb_animal_command_owned_02c82195e3c7")&&url.equals("jdbc:postgresql://127.0.0.1:52620/"+db)
                    &&user.equals("pb_lost_search_reader")&&marker.matches("[0-9a-f]{32}"),"Only Root's exact new loopback fixture and reader are allowed");
            return new Settings(url,db,user,required(props,"password"),marker);
        }
        private static String required(Properties props,String key){String v=props.getProperty(key);check(v!=null&&!v.isBlank(),"Every owned reader setting is required");return v;}
        @Override public String toString(){return "OwnedLostSearchSettings[redacted]";}
    }
}
