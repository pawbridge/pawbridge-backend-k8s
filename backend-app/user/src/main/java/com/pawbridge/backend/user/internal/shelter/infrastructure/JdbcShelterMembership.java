package com.pawbridge.backend.user.internal.shelter.infrastructure;

import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException;
import static com.pawbridge.backend.user.internal.shelter.application.ShelterMembershipRejectedException.Reason.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcShelterMembership implements ShelterMembership {
    private static final String COLUMNS="id,user_id,shelter_name,status,requested_at,reviewed_by,reviewed_at,review_note,care_reg_no";
    private final JdbcOperations jdbc;
    private final TransactionOperations write,read,statistics;
    private final ShelterLookup shelters;
    private final Clock clock;
    private final Supplier<LocalDateTime> now;
    public JdbcShelterMembership(JdbcOperations jdbc,TransactionOperations write,TransactionOperations read,
            TransactionOperations statistics,ShelterLookup shelters,Clock clock){
        this(jdbc,write,read,statistics,shelters,clock,LocalDateTime::now);
    }
    public JdbcShelterMembership(JdbcOperations jdbc,TransactionOperations write,TransactionOperations read,
            TransactionOperations statistics,ShelterLookup shelters,Clock clock,Supplier<LocalDateTime> now){
        this.jdbc=jdbc;this.write=write;this.read=read;this.statistics=statistics;this.shelters=shelters;
        this.clock=clock.withZone(ZoneId.of("Asia/Seoul"));this.now=now;
    }
    public Application submit(long current,String name){return write.execute(tx->{
        User user=user(current,true); if(user==null)throw rejected(TOKEN_INVALID);
        if(!"ROLE_USER".equals(user.role()))throw rejected(FORBIDDEN);
        if(count("SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id=? AND status='PENDING'",current)>0)throw rejected(CONFLICT);
        String stripped=text(name,100);LocalDateTime requested=now.get();
        Long id=jdbc.queryForObject("INSERT INTO pawbridge_user.shelter_applications(user_id,shelter_name,status,requested_at) VALUES(?,?,'PENDING',?) RETURNING id",
                Long.class,current,stripped,utcTimestamp(requested));
        return new Application(id,current,stripped,Status.PENDING,requested,null,null,null,null);
    });}
    public Page<Application> mine(long current,int number,int size){return read.execute(tx->{
        authenticated(current);page(number,size);
        return applications(" WHERE user_id=?",new Object[]{current},number,size).map(Row::view);
    });}
    public Page<AdminApplication> list(long current,Status status,int number,int size){return read.execute(tx->{
        administrator(current);page(number,size);
        Rows rows=applications(status==null?"":" WHERE status=?",status==null?new Object[]{}:new Object[]{status.name()},number,size);
        List<User> applicants=List.of();
        if(!rows.content().isEmpty()){
            String marks=String.join(",",java.util.Collections.nCopies(rows.content().size(),"?"));
            applicants=jdbc.query("SELECT user_id,name,email,role FROM pawbridge_user.users WHERE user_id IN ("+marks+")",USER,
                    rows.content().stream().map(Row::userId).toArray());
        }
        List<User> found=applicants;
        return rows.map(row->admin(row,found.stream().filter(u->u.id()==row.userId()).findFirst().orElse(null)));
    });}
    public AdminApplication detail(long current,long id){return read.execute(tx->{
        administrator(current);Row row=find(id,false);if(row==null)throw rejected(NOT_FOUND);
        return admin(row,user(row.userId(),false));
    });}
    public AdminApplication approve(long current,long id,String registration,String note){return write.execute(tx->{
        User reviewer=administrator(current);String care=text(registration,50),review=text(note,1000);
        Locked locked=lock(id);if(!"ROLE_USER".equals(locked.user().role()))throw rejected(CONFLICT);
        Shelter shelter;
        try{shelter=shelters.find(care);}catch(MissingShelter missing){throw rejected(SHELTER_NOT_FOUND);}
        catch(RuntimeException unavailable){throw rejected(SHELTER_UNAVAILABLE);}
        if(shelter==null||shelter.id()==null||!care.equals(shelter.careRegNo()))throw rejected(SHELTER_UNAVAILABLE);
        LocalDateTime reviewed=now.get();Row updated=review(locked.row(),Status.APPROVED,reviewer.id(),reviewed,review,care);
        if(jdbc.update("UPDATE pawbridge_user.shelter_applications SET status='APPROVED',reviewed_by=?,reviewed_at=?,review_note=?,care_reg_no=? WHERE id=?",
                reviewer.id(),utcTimestamp(reviewed),review,care,id)!=1)throw stale();
        // JPA auditing runs only for the changed user; nickname/password remain untouched.
        if(jdbc.update("UPDATE pawbridge_user.users SET role='ROLE_SHELTER',care_reg_no=?,updated_at=? WHERE user_id=?",
                care,utcTimestamp(now.get()),locked.user().id())!=1)throw stale();
        return admin(updated,locked.user());
    });}
    public AdminApplication reject(long current,long id,String reason){return write.execute(tx->{
        User reviewer=administrator(current);Locked locked=lock(id);String review=text(reason,1000);LocalDateTime reviewed=now.get();
        Row updated=review(locked.row(),Status.REJECTED,reviewer.id(),reviewed,review,locked.row().careRegNo());
        if(jdbc.update("UPDATE pawbridge_user.shelter_applications SET status='REJECTED',reviewed_by=?,reviewed_at=?,review_note=? WHERE id=?",
                reviewer.id(),utcTimestamp(reviewed),review,id)!=1)throw stale();
        return admin(updated,user(locked.row().userId(),false));
    });}
    public Page<Member> members(long current,String registration,int number,int size){return read.execute(tx->{
        administrator(current);String care=text(registration,50);page(number,size);
        List<User> users=jdbc.query("SELECT user_id,name,email,role FROM pawbridge_user.users WHERE care_reg_no=? AND role='ROLE_SHELTER' ORDER BY user_id DESC LIMIT ? OFFSET ?",
                USER,care,size,offset(number,size));
        long total=total(users.size(),number,size,()->count("SELECT count(*) FROM pawbridge_user.users WHERE care_reg_no=? AND role='ROLE_SHELTER'",care));
        List<Member> content=new ArrayList<>();
        for(User user:users){List<Long> approvals=jdbc.query("SELECT id FROM pawbridge_user.shelter_applications WHERE user_id=? AND care_reg_no=? AND status='APPROVED' ORDER BY id DESC LIMIT 1",
                (rs,n)->rs.getLong("id"),user.id(),care);content.add(new Member(user.id(),user.name(),user.email(),approvals.isEmpty()?null:approvals.getFirst()));}
        return new Page<>(content,total,number,size);
    });}
    public Statistics statistics(long current,LocalDate start,LocalDate end){return statistics.execute(tx->{
        administrator(current);
        if(start==null||end==null||start.isAfter(end)||end.isAfter(LocalDate.now(clock))||ChronoUnit.DAYS.between(start,end)>=366)throw rejected(INVALID_INPUT);
        LocalDate previous=start.minusDays(1);
        List<Daily> daily=jdbc.query("SELECT CAST(requested_at+INTERVAL '9 hours' AS date) AS requested_date,count(*) AS requested_count FROM pawbridge_user.shelter_applications WHERE requested_at>=? AND requested_at<? GROUP BY CAST(requested_at+INTERVAL '9 hours' AS date) ORDER BY CAST(requested_at+INTERVAL '9 hours' AS date)",
                (rs,n)->new Daily(rs.getObject("requested_date",LocalDate.class),rs.getLong("requested_count")),utcTimestamp(previous.atStartOfDay()),utcTimestamp(end.plusDays(1).atStartOfDay()));
        List<ReviewCount> reviews=jdbc.query("SELECT status,count(*) AS review_count FROM pawbridge_user.shelter_applications WHERE reviewed_at>=? AND reviewed_at<? AND status IN ('APPROVED','REJECTED') GROUP BY status",
                (rs,n)->new ReviewCount(Status.valueOf(rs.getString("status")),rs.getLong("review_count")),utcTimestamp(start.atStartOfDay()),utcTimestamp(end.plusDays(1).atStartOfDay()));
        return new Statistics(start,end,daily.stream().filter(d->!d.date().isBefore(start)).toList(),daily.stream().filter(d->d.date().equals(previous)).mapToLong(Daily::count).sum(),
                count("SELECT count(*) FROM pawbridge_user.shelter_applications WHERE status='PENDING'"),
                reviews.stream().filter(r->r.status()==Status.APPROVED).mapToLong(ReviewCount::count).sum(),reviews.stream().filter(r->r.status()==Status.REJECTED).mapToLong(ReviewCount::count).sum());
    });}
    private User authenticated(long current){User found=user(current,false);if(found==null)throw rejected(TOKEN_INVALID);return found;}
    private User administrator(long current){User found=authenticated(current);if(!"ROLE_ADMIN".equals(found.role()))throw rejected(FORBIDDEN);return found;}
    private User user(long id,boolean lock){List<User> rows=jdbc.query("SELECT user_id,name,email,role FROM pawbridge_user.users WHERE user_id=?"+(lock?" FOR UPDATE":""),USER,id);return rows.isEmpty()?null:rows.getFirst();}
    private Row find(long id,boolean lock){List<Row> rows=jdbc.query("SELECT "+COLUMNS+" FROM pawbridge_user.shelter_applications WHERE id=?"+(lock?" FOR UPDATE":""),APPLICATION,id);return rows.isEmpty()?null:rows.getFirst();}
    private Locked lock(long id){Row first=find(id,false);if(first==null)throw rejected(NOT_FOUND);User applicant=user(first.userId(),true);if(applicant==null)throw rejected(NOT_FOUND);
        Row refreshed=find(id,true);if(refreshed==null)throw stale();if(refreshed.status()!=Status.PENDING)throw rejected(CONFLICT);return new Locked(refreshed,applicant);}
    private Rows applications(String where,Object[] args,int number,int size){Object[] pageArgs=java.util.Arrays.copyOf(args,args.length+2);pageArgs[args.length]=size;pageArgs[args.length+1]=offset(number,size);
        List<Row> rows=jdbc.query("SELECT "+COLUMNS+" FROM pawbridge_user.shelter_applications"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",APPLICATION,pageArgs);
        return new Rows(rows,total(rows.size(),number,size,()->count("SELECT count(*) FROM pawbridge_user.shelter_applications"+where,args)),number,size);}
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private static long total(int rows,int number,int size,java.util.function.LongSupplier count){return rows<size&&(number==0||rows>0)?offset(number,size)+rows:count.getAsLong();}
    private static long offset(int number,int size){return (long)number*size;}
    private static void page(int number,int size){if(number<0||size<1||size>100)throw rejected(INVALID);if(offset(number,size)>Integer.MAX_VALUE)throw new org.springframework.dao.InvalidDataAccessApiUsageException("Page offset exceeds the supported range");}
    private static String text(String value,int max){if(value==null||value.isBlank()||value.strip().length()>max)throw rejected(INVALID);return value.strip();}
    private static ShelterMembershipRejectedException rejected(ShelterMembershipRejectedException.Reason reason){return new ShelterMembershipRejectedException(reason);}
    private static RuntimeException stale(){return new OptimisticLockingFailureException("Shelter membership row changed concurrently");}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static org.springframework.jdbc.support.SqlValue utcTimestamp(LocalDateTime time){return new org.springframework.jdbc.support.SqlValue(){
        public void setValue(java.sql.PreparedStatement statement,int index)throws SQLException{statement.setTimestamp(index,Timestamp.valueOf(time),utc());}
        public void cleanup(){ }
    };}
    private static LocalDateTime time(ResultSet rs,String column)throws SQLException{Timestamp value=rs.getTimestamp(column,utc());return value==null?null:value.toLocalDateTime();}
    private static final RowMapper<User> USER=(rs,n)->new User(rs.getLong("user_id"),rs.getString("name"),rs.getString("email"),rs.getString("role"));
    private static final RowMapper<Row> APPLICATION=(rs,n)->new Row(rs.getLong("id"),rs.getLong("user_id"),rs.getString("shelter_name"),Status.valueOf(rs.getString("status")),
            time(rs,"requested_at"),rs.getObject("reviewed_by",Long.class),time(rs,"reviewed_at"),rs.getString("review_note"),rs.getString("care_reg_no"));
    private static Row review(Row row,Status status,long reviewer,LocalDateTime at,String note,String care){return new Row(row.id(),row.userId(),row.shelterName(),status,row.requestedAt(),reviewer,at,note,care);}
    private static AdminApplication admin(Row row,User user){return new AdminApplication(row.view(),user==null?"탈퇴 회원":user.name(),user==null?null:user.email(),row.reviewNote());}
    private record User(long id,String name,String email,String role) { }
    private record Locked(Row row,User user) { }
    private record ReviewCount(Status status,long count) { }
    private record Row(Long id,Long userId,String shelterName,Status status,LocalDateTime requestedAt,Long reviewedBy,LocalDateTime reviewedAt,String reviewNote,String careRegNo){
        Application view(){return new Application(id,userId,shelterName,status,requestedAt,reviewedAt,reviewedBy,status==Status.REJECTED?reviewNote:null,careRegNo);}}
    private record Rows(List<Row> content,long total,int number,int size){<T> Page<T> map(java.util.function.Function<Row,T> mapper){return new Page<>(content.stream().map(mapper).toList(),total,number,size);}}
}
