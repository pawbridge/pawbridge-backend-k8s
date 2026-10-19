package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteView;
import com.pawbridge.backend.community.internal.note.application.PrivateNotePage;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListRejectedException;
import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteListReader;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.api.ContactMemberUnavailableException;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMembersQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.F23LegacyPrivateNoteListService;
import com.pawbridge.communityservice.contact.PrivateNoteModels;
import com.pawbridge.communityservice.contact.PrivateNoteRepository;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import feign.FeignException;
import feign.Request;
import feign.Response;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockReader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Compares the selected original list with restricted read transactions on the owned synthetic fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlPrivateNoteListCompatibilityTest {
    private static final long BASE=9_977_100L;
    private static final Instant NOW=Instant.parse("2031-01-01T00:00:00.123456Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private final Set<Long> ownedUsers=new LinkedHashSet<>(),ownedMembers=new LinkedHashSet<>(),ownedPending=new LinkedHashSet<>();
    private final Set<UUID> ownedNotes=new LinkedHashSet<>();
    private final Set<Mailbox> ownedMailboxes=new LinkedHashSet<>();
    private final Set<Block> ownedBlocks=new LinkedHashSet<>();
    private final List<String> phases=new ArrayList<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcTemplate jdbc,reference;
    private ContactMemberQuery contacts;
    private PrivateNoteListReader notes;
    private ContactMembersQuery batches;
    private NoteBlockReader blocks;
    private ListPrivateNotes candidate;
    private F23LegacyPrivateNoteListService original;
    private UserServiceClient oldClient;
    @BeforeAll void openOnlyOwnedFixtureAndRestrictedSingleConnection() throws Exception {
        assertThat(System.getProperty("user.timezone")).isIn("UTC","Asia/Seoul");
        owner=F3PostgresqlFixture.open();verifyOwnership();
        var config=new HikariConfig();config.setJdbcUrl(owner.reads().jdbcUrl());config.setUsername(owner.reads().applicationUser());config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true);config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(2_000);config.setValidationTimeout(1_000);config.setPoolName("f23-private-note-list-reader");
        reader=new HikariDataSource(config);jdbc=new JdbcTemplate(reader);jdbc.setQueryTimeout(3);
        reference=new JdbcTemplate(new AbstractDataSource(){
            public Connection getConnection() throws SQLException{return admin();}
            public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Only owned reference connection");}
        });reference.setQueryTimeout(3);
        var manager=new JdbcTransactionManager(reader);manager.setEnforceReadOnly(true);var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setTimeout(3);
        var single=new JdbcContactMemberQuery(jdbc,tx);var list=new JdbcPrivateNoteListReader(jdbc,tx);var block=new JdbcNoteBlockReader(jdbc,tx);
        contacts=id->{returned();phases.add("member");try{return single.get(id);}finally{returned();}};
        var batch=new JdbcContactMembersQuery(jdbc,tx);
        batches=ids->{returned();phases.add("batch");try{return batch.getAll(ids);}finally{returned();}};
        notes=new PrivateNoteListReader(){
            public long count(long id,String box,boolean favorite,Instant now){returned();phases.add("count");try{return list.count(id,box,favorite,now);}finally{returned();}}
            public List<PrivateNoteReader.StoredNote> read(long id,String box,boolean favorite,int page,Instant now){returned();phases.add("list");try{return list.read(id,box,favorite,page,now);}finally{returned();}}
        };
        blocks=(first,second)->{returned();phases.add("block");try{return block.blocked(first,second);}finally{returned();}};
        candidate=new ListPrivateNotes(contacts,notes,batches,blocks,CLOCK);
        var oldContacts=new com.pawbridge.userservice.contact.ContactMemberQuery(jdbc);oldClient=mock(UserServiceClient.class);
        when(oldClient.getContactMember(anyLong())).thenAnswer(call->{returned();try{var value=oldContacts.get(call.getArgument(0,Long.class));return new PrivateNoteModels.ContactMember(value.userId(),value.nickname(),value.active(),value.deletionPending());}catch(RuntimeException failure){throw oldTransportFailure();}finally{returned();}});
        when(oldClient.getContactMembers(anyList())).thenAnswer(call->{returned();try{return oldContacts.getAll(call.getArgument(0)).stream().map(value->new PrivateNoteModels.ContactMember(value.userId(),value.nickname(),value.active(),value.deletionPending())).toList();}catch(RuntimeException failure){throw oldTransportFailure();}finally{returned();}});
        original=new F23LegacyPrivateNoteListService(new PrivateNoteRepository(reference),oldClient,CLOCK);returned();
    }
    private RuntimeException oldTransportFailure(){var request=Request.create(Request.HttpMethod.GET,"https://fixture.invalid/contact",Map.of(),new byte[0],StandardCharsets.UTF_8,null);return FeignException.errorStatus("synthetic",Response.builder().status(500).reason("synthetic").request(request).headers(Map.of()).build());}
    @AfterEach void removeOnlySuccessfulOwnedKeys()throws Exception{
        if(owner!=null){
            try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.private_note_blocks WHERE member_id=? AND blocked_id=?")){for(Block b:ownedBlocks){s.setLong(1,b.first());s.setLong(2,b.second());s.executeUpdate();}}
            try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id=? AND note_id=?")){for(Mailbox m:ownedMailboxes){s.setLong(1,m.member());s.setObject(2,m.note());s.executeUpdate();}}
            try(Connection c=admin();var s=c.prepareStatement("DELETE FROM pawbridge_community.private_notes WHERE note_id=?")){for(UUID id:ownedNotes){s.setObject(1,id);s.executeUpdate();}}
            deleteIds("pawbridge_community.private_note_members","member_id",ownedMembers);deleteIds("pawbridge_user.contact_deletions","user_id",ownedPending);deleteIds("pawbridge_user.users","user_id",ownedUsers);
        }
        ownedBlocks.clear();ownedMailboxes.clear();ownedNotes.clear();ownedPending.clear();ownedMembers.clear();ownedUsers.clear();phases.clear();if(reader!=null)returned();
    }
    @AfterAll void closeOnlyOwnPool(){if(reader!=null)reader.close();}
    @ParameterizedTest @ValueSource(strings={"INBOX","SENT"})
    void givenTwelveOwnedNotesAndRepeatedActiveCounterpart__whenPagingBothMailboxes__thenCountAndPageSizeUseOneBatchAndEachInboxRowChecksBlock(String box)throws Exception{
        people("  raw nickname  ");
        for(int i=1;i<=12;i++)note(i,box.equals("INBOX")?BASE+2:BASE+1,box.equals("INBOX")?BASE+1:BASE+2,BASE+1,box,NOW.minusSeconds(i),NOW.plusSeconds(60),i==12?NOW:null,i%2==0,"POST",23L);
        String before=snapshot();var first=same(box,false,0);assertThat(first.totalElements()).isEqualTo(12);assertThat(first.totalPages()).isEqualTo(2);assertThat(first.number()).isZero();assertThat(first.size()).isEqualTo(10);assertThat(first.content()).hasSize(10);
        assertThat(first.content()).extracting(PrivateNoteView::noteId).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(3,12).mapToObj(i->uuid(15-i)).toList());
        var expected=new ArrayList<>(List.of("member","count","list","batch"));if(box.equals("INBOX"))for(int i=0;i<10;i++)expected.add("block");
        assertThat(phases).containsExactlyElementsOf(expected);assertThat(phases).hasSize(box.equals("INBOX")?14:4);
        var second=same(box,false,1);assertThat(second.content()).hasSize(2);assertThat(second.number()).isOne();assertThat(second.content()).extracting(PrivateNoteView::noteId).containsExactly(uuid(2),uuid(1));
        var empty=same(box,false,2);assertThat(empty.content()).isEmpty();assertThat(empty.totalElements()).isEqualTo(12);assertThat(empty.totalPages()).isEqualTo(2);assertThat(phases).containsExactly("member","count","list");
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void givenFavoriteForeignDeletedAndExpiryBoundaryRows__whenListing__thenOnlyLiveOwnedRequestedMailboxAndFavoriteMatch(boolean favorite)throws Exception{
        people("raw");
        note(1,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusNanos(1000),null,true,null,null);
        note(2,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,null,null);
        note(3,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW,null,true,null,null);
        note(4,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.minusNanos(1000),null,true,null,null);
        note(5,BASE+2,BASE+1,BASE+5,"INBOX",NOW,NOW.plusSeconds(60),null,true,null,null);
        UUID removed=note(6,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,true,null,null);
        execute("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id="+(BASE+1)+" AND note_id='"+removed+"'");
        note(7,BASE+1,BASE+2,BASE+1,"SENT",NOW,NOW.plusSeconds(60),null,true,null,null);
        String before=snapshot();var result=same("INBOX",favorite,0);assertThat(result.totalElements()).isEqualTo(favorite?1:2);assertThat(result.content()).extracting(PrivateNoteView::noteId).containsExactlyElementsOf(favorite?List.of(uuid(1)):List.of(uuid(2),uuid(1)));
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"null","missing","pending","blank","space"})
    void givenNullableMissingPendingOrRawActiveCounterpart__whenListing__thenKeepOwnedBodyAndBatchOnlyNonnullIdsWithoutFilteringRows(String kind)throws Exception{
        people(kind.equals("blank")?"":kind.equals("space")?"   ":"raw");Long sender=switch(kind){case "null"->null;case "missing"->BASE+4;case "pending"->BASE+3;default->BASE+2;};
        note(1,sender,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,null,null);String before=snapshot();var result=same("INBOX",false,0);var row=result.content().get(0);boolean active=kind.equals("blank")||kind.equals("space");
        assertThat(row.body()).isEqualTo("  F23 private synthetic body\nsecond line  ");assertThat(row.counterpartId()).isEqualTo(active?BASE+2:null);assertThat(row.counterpartNickname()).isEqualTo(active?(kind.equals("blank")?"":"   "):"탈퇴한 회원");assertThat(row.canReply()).isEqualTo(active);
        assertThat(phases.stream().filter("batch"::equals).count()).isEqualTo(kind.equals("null")?0:1);assertThat(phases.stream().filter("block"::equals).count()).isEqualTo(active?1:0);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void givenCountZeroThenOwnedRowAppearsBetweenQueries__whenListing__thenBothStillReadContentWithoutClampingCountOrAddingOuterTransaction()throws Exception{
        people("raw");member(BASE+1);member(BASE+2);
        var legacy=new F23LegacyPrivateNoteListService(new PrivateNoteRepository(reference){
            @Override public long count(long id,String box,boolean favorite,Instant now){
                long value=super.count(id,box,favorite,now);returned();insertAfterCount();return value;
            }
        },oldClient,CLOCK);
        String before=snapshot();var expected=legacy.list(BASE+1,"INBOX",false,0);returned();
        assertThat(expected.totalElements()).isZero();assertThat(expected.content()).hasSize(1);
        removeOnlyNoteOne();assertThat(snapshot()).isEqualTo(before);phases.clear();
        var hooked=new PrivateNoteListReader(){
            public long count(long id,String box,boolean favorite,Instant now){long value=notes.count(id,box,favorite,now);returned();insertAfterCount();return value;}
            public List<PrivateNoteReader.StoredNote> read(long id,String box,boolean favorite,int page,Instant now){return notes.read(id,box,favorite,page,now);}
        };
        var actual=new ListPrivateNotes(contacts,hooked,batches,blocks,CLOCK).read(BASE+1,"INBOX",false,0);returned();assertSamePage(expected,actual);
        assertThat(phases).containsExactly("member","count","list","batch","block");removeOnlyNoteOne();assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"count","list","batch","block"})
    void givenSelectRevokedAtOneSpecificCompletedBoundary__whenListing__thenOnlyThatStageFailsNoPartialPageAndRestoreRecovers(String stage)throws Exception{
        people("raw");note(1,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,null,null);String before=snapshot();same("INBOX",false,0);var reached=new AtomicBoolean();
        String table=stage.equals("batch")?"pawbridge_user.users":stage.equals("block")?"pawbridge_community.private_note_blocks":"pawbridge_community.private_note_mailboxes";
        String column=switch(stage){case "count"->"favorite";case "list"->"created_at";case "batch"->"nickname";default->"member_id";};
        var hookedNotes=new PrivateNoteListReader(){
            public long count(long id,String box,boolean favorite,Instant now){long count=notes.count(id,box,favorite,now);if(stage.equals("list")){revoke(table,column);reached.set(true);}return count;}
            public List<PrivateNoteReader.StoredNote> read(long id,String box,boolean favorite,int page,Instant now){var found=notes.read(id,box,favorite,page,now);if(stage.equals("batch")){revoke(table,column);reached.set(true);}return found;}
        };
        ContactMembersQuery hookedBatch=ids->{var found=batches.getAll(ids);if(stage.equals("block")){revoke(table,column);reached.set(true);}return found;};
        if(stage.equals("count")){revoke(table,column);reached.set(true);}
        phases.clear();
        try{
            var attempt=new ListPrivateNotes(contacts,hookedNotes,hookedBatch,blocks,CLOCK);
            if(stage.equals("batch"))assertThatThrownBy(()->attempt.read(BASE+1,"INBOX",false,0)).isInstanceOf(ContactMemberUnavailableException.class);
            else assertThatThrownBy(()->attempt.read(BASE+1,"INBOX",false,0)).isInstanceOf(DataAccessException.class);
            returned();assertThat(reached).isTrue();var expected=new ArrayList<>(List.of("member","count"));
            if(!stage.equals("count"))expected.add("list");if(stage.equals("batch")||stage.equals("block"))expected.add("batch");if(stage.equals("block"))expected.add("block");
            assertThat(phases).containsExactlyElementsOf(expected);assertThat(snapshot()).isEqualTo(before);
            if(!stage.equals("batch"))assertThat(original.list(BASE+1,"INBOX",false,0).content()).hasSize(1);
        }finally{execute("GRANT SELECT("+column+") ON "+table+" TO pawbridge_f2_reader");}
        same("INBOX",false,0);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void givenMailboxUuidSortDiffersFromNoteAndJavaSignedOrder__whenListingAndSerializing__thenPreservePgOrderAndNoteReadInstantsWithoutZoneShift()throws Exception{
        people("raw");UUID small=note(1,BASE+2,BASE+1,BASE+1,"INBOX",NOW.plusSeconds(1),NOW.plusSeconds(60),null,false,null,null);
        UUID high=UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff3");
        noteWithId(high,BASE+2,BASE+1,BASE+1,"INBOX",NOW.minusSeconds(1),NOW.plusSeconds(60),NOW,false,"REPORT",42L);
        String before=snapshot();var result=same("INBOX",false,0);assertThat(result.content()).extracting(PrivateNoteView::noteId).containsExactly(high,small);
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var json=context.getBean(ObjectMapper.class).valueToTree(result);assertThat(json.size()).isEqualTo(5);
            assertThat(json.path("content").get(0).size()).isEqualTo(10);assertThat(json.path("content").get(0).path("createdAt").asText()).isEqualTo("2030-12-31T23:59:59.123456Z");
            assertThat(json.path("content").get(0).path("readAt").asText()).isEqualTo("2031-01-01T00:00:00.123456Z");
        }assertThat(snapshot()).isEqualTo(before);
    }
    @Test @Order(Integer.MAX_VALUE)
    void givenOriginalIssuerAndOwnedFile__whenPreparingOnlyTheUsedJarPrincipal__thenPreservePreviousKeysAndPermissions() throws Exception {
        verifyOwnership(); String run=F3PostgresqlFixture.required("GITHUB_RUN_ID")+"-"+F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path file=Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(file).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-"+run+".json"));
        assertThat(Files.isSymbolicLink(file)).isFalse(); assertThat(Files.isRegularFile(file)).isTrue(); assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper=new ObjectMapper(); Map<String,String> tokens=new LinkedHashMap<>(mapper.readValue(Files.readString(file),new TypeReference<Map<String,String>>(){})); var oldKeys=Set.copyOf(tokens.keySet());
        String secret=F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var user=User.builder().userId(9_977_001L).email("f23-token@fixture.invalid").name("F23 synthetic").nickname("F23token").provider("GOOGLE").role(Role.ROLE_USER).build();
        String token=new JwtProvider(secret,600_000,600_000).createAccessToken(user);
        assertThat(new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).authenticate("Bearer "+token)).isEqualTo(9_977_001L);
        tokens.put("F23_CALLER",token); assertThat(tokens.keySet()).containsAll(oldKeys); Files.writeString(file,mapper.writeValueAsString(tokens)); Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); returned();
    }
    private PrivateNotePage same(String box,boolean favorite,int page){
        var expected=original.list(BASE+1,box,favorite,page);returned();phases.clear();var actual=candidate.read(BASE+1,box,favorite,page);returned();assertSamePage(expected,actual);return actual;
    }
    private void assertSamePage(PrivateNoteModels.NotePage expected,PrivateNotePage actual){
        try(var context=new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)){
            var mapper=context.getBean(ObjectMapper.class);var oldJson=mapper.valueToTree(expected);var newJson=mapper.valueToTree(actual);
            assertThat(newJson.size()).isEqualTo(5);assertThat(newJson).isEqualTo(oldJson);for(var row:newJson.path("content"))assertThat(row.size()).isEqualTo(10);
        }
    }
    private void insertAfterCount(){try{note(1,BASE+2,BASE+1,BASE+1,"INBOX",NOW,NOW.plusSeconds(60),null,false,null,null);}catch(SQLException failure){throw new IllegalStateException(failure);}}
    private void removeOnlyNoteOne()throws SQLException{
        UUID id=uuid(1);execute("DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id="+(BASE+1)+" AND note_id='"+id+"'");
        execute("DELETE FROM pawbridge_community.private_notes WHERE note_id='"+id+"'");ownedMailboxes.remove(new Mailbox(BASE+1,id));ownedNotes.remove(id);
    }
    private void people(String nickname)throws SQLException{user(BASE+1,"F23caller");user(BASE+2,nickname);user(BASE+3,"F23pending");pending(BASE+3);}
    private UUID uuid(int serial){return new UUID(23L,serial);}
    private void member(long id)throws SQLException{if(!ownedMembers.contains(id))try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_members(member_id) VALUES(?)")){s.setLong(1,id);s.executeUpdate();ownedMembers.add(id);}}
    private UUID note(int serial,Long sender,Long recipient,long mailboxOwner,String direction,Instant noteTime,Instant expires,Instant readAt,boolean favorite,String contextType,Long contextId)throws SQLException{
        return noteWithId(uuid(serial),sender,recipient,mailboxOwner,direction,noteTime,expires,readAt,favorite,contextType,contextId);
    }
    private UUID noteWithId(UUID id,Long sender,Long recipient,long mailboxOwner,String direction,Instant noteTime,Instant expires,Instant readAt,boolean favorite,String contextType,Long contextId)throws SQLException{
        if(sender!=null)member(sender);if(recipient!=null)member(recipient);member(mailboxOwner);
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,context_type,context_id,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)")){s.setObject(1,id);s.setObject(2,sender);s.setObject(3,recipient);s.setString(4,"  F23 private synthetic body\nsecond line  ");s.setString(5,contextType);s.setObject(6,contextId);s.setObject(7,java.time.OffsetDateTime.ofInstant(noteTime,ZoneOffset.UTC));s.setObject(8,java.time.OffsetDateTime.ofInstant(expires,ZoneOffset.UTC));s.executeUpdate();ownedNotes.add(id);}
        try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES(?,?,?,?,?,?)")){s.setLong(1,mailboxOwner);s.setObject(2,id);s.setString(3,direction);s.setObject(4,readAt==null?null:java.time.OffsetDateTime.ofInstant(readAt,ZoneOffset.UTC));s.setBoolean(5,favorite);s.setObject(6,java.time.OffsetDateTime.ofInstant(NOW.plusSeconds(40),ZoneOffset.UTC));s.executeUpdate();ownedMailboxes.add(new Mailbox(mailboxOwner,id));}return id;
    }
    private void block(long first,long second)throws SQLException{member(first);member(second);try(Connection c=admin();var s=c.prepareStatement("INSERT INTO pawbridge_community.private_note_blocks(member_id,blocked_id,created_at) VALUES(?,?,?)")){s.setLong(1,first);s.setLong(2,second);s.setObject(3,java.time.OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC));s.executeUpdate();ownedBlocks.add(new Block(first,second));}}
    private void revoke(String table,String column){try{execute("REVOKE SELECT("+column+") ON "+table+" FROM pawbridge_f2_reader");}catch(SQLException failure){throw new IllegalStateException(failure);}}
    private void user(long id, String nickname) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setString(2, "f23-" + id + "@fixture.invalid"); s.setString(3, "F23 synthetic");
            s.setString(4, nickname); s.setString(5, "GOOGLE"); s.setString(6, "ROLE_USER"); s.executeUpdate(); ownedUsers.add(id);
        }
    }
    private void pending(long id) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES(?)")) {
            s.setLong(1, id); s.executeUpdate(); ownedPending.add(id);
        }
    }
    private void deleteIds(String table, String column, Set<Long> ids) throws SQLException {
        if (!Set.of("pawbridge_user.contact_deletions.user_id", "pawbridge_community.private_note_members.member_id", "pawbridge_user.users.user_id").contains(table + "." + column))
            throw new IllegalArgumentException("Only owned F23 key cleanup is supported");
        try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM " + table + " WHERE " + column + "=?")) {
            for (long id : ids) { s.setLong(1, id); s.executeUpdate(); }
        }
    }
    private Connection admin() throws SQLException {
        Connection c = owner.admin();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT 16,value,owner FROM pawbridge_f16_fixture.marker UNION ALL SELECT 17,value,owner FROM pawbridge_f17_fixture.marker ORDER BY 1")) {
            String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{16, 17}) {
                assertThat(F3PostgresqlFixture.required("F" + stage + "_CI_OWNER")).isEqualTo(run);
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(stage);
                assertThat(rows.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1"); assertThat(rows.getString(3)).isEqualTo(run);
            }
            assertThat(rows.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) { c.close(); throw failure; }
        return c;
    }
    private void verifyOwnership() { try (Connection ignored = admin()) { } catch (SQLException e) { throw new IllegalStateException(e); } }
    private void execute(String sql) throws SQLException { try (Connection c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    private String snapshot() throws SQLException {
        String sql = "SELECT md5(json_build_object("
                + "'private_notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]') FROM pawbridge_community.private_notes n),"
                + "'mailboxes',(SELECT COALESCE(json_agg(m ORDER BY member_id,note_id),'[]') FROM pawbridge_community.private_note_mailboxes m),"
                + "'pending',(SELECT COALESCE(json_agg(d ORDER BY user_id),'[]') FROM pawbridge_user.contact_deletions d),"
                + "'members',(SELECT COALESCE(json_agg(m ORDER BY member_id),'[]') FROM pawbridge_community.private_note_members m),"
                + "'blocks',(SELECT COALESCE(json_agg(b ORDER BY member_id,blocked_id),'[]') FROM pawbridge_community.private_note_blocks b),"
                + "'visits',(SELECT COALESCE(json_agg(v ORDER BY visit_date),'[]') FROM pawbridge_community.daily_visit_count v),"
                + "'videos',(SELECT COALESCE(json_agg(v ORDER BY id),'[]') FROM pawbridge_community.home_videos v),"
                + "'board',(SELECT COALESCE(json_agg(b ORDER BY id),'[]') FROM pawbridge_community.home_video_board b),"
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
                + "'favorites',(SELECT COALESCE(json_agg(f ORDER BY favorite_id),'[]') FROM pawbridge_user.favorites f),"
                + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                + "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_community.comments c),"
                + "'f4comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_f4_fixture.comments c),"
                + "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]') FROM pawbridge_f4_fixture.private_notes n),"
                + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))::text)";
        try (Connection c = admin(); var s = c.createStatement(); var rows = s.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }
    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne(); assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private record Mailbox(long member,UUID note) { }
    private record Block(long first,long second) { }
}
