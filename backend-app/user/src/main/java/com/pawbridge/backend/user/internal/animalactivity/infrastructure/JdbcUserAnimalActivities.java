package com.pawbridge.backend.user.internal.animalactivity.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.animalactivity.application.*;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcUserAnimalActivities implements UserAnimalActivities {
    private final JdbcOperations jdbc;
    private final TransactionOperations write,read;
    private final AnimalLookup animals;
    private final Supplier<UUID> ids;
    private final Supplier<LocalDateTime> now;
    private final ObjectMapper payloads=new ObjectMapper();
    public JdbcUserAnimalActivities(JdbcOperations jdbc,TransactionOperations write,TransactionOperations read,AnimalLookup animals){this(jdbc,write,read,animals,UUID::randomUUID,LocalDateTime::now);}
    public JdbcUserAnimalActivities(JdbcOperations jdbc,TransactionOperations write,TransactionOperations read,AnimalLookup animals,Supplier<UUID> ids,Supplier<LocalDateTime> now){
        this.jdbc=jdbc;this.write=write;this.read=read;this.animals=animals;this.ids=ids;this.now=now;
    }
    public Favorite add(long current,long animal){return write.execute(tx->{
        try{
            if(exists(current,animal))throw new FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.ALREADY_EXISTS);
            requireUser(current);
            LocalDateTime created=now.get();
            Long id=jdbc.queryForObject("INSERT INTO pawbridge_user.favorites(user_id,animal_id,created_at) VALUES(?,?,?) RETURNING favorite_id",Long.class,current,animal,timestamp(created));
            event(current,animal,"FAVORITE_ADDED");
            // Auditing's in-memory LocalDateTime is returned before any DB precision refresh, as in JPA.
            return new Favorite(id,current,animal,created);
        }catch(DataIntegrityViolationException conflict){throw new FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.ALREADY_EXISTS);}
    });}
    public void remove(long current,long animal){write.executeWithoutResult(tx->{
        if(!exists(current,animal))throw new FavoriteActivityRejectedException(FavoriteActivityRejectedException.Reason.NOT_FOUND);
        int deleted=jdbc.update("DELETE FROM pawbridge_user.favorites WHERE user_id=? AND animal_id=?",current,animal);
        if(deleted>0)event(current,animal,"FAVORITE_REMOVED");
    });}
    public Favorites favorites(long current){return read.execute(tx->{
        requireUser(current);
        List<Favorite> favorites=jdbc.query("SELECT favorite_id,user_id,animal_id,created_at FROM pawbridge_user.favorites WHERE user_id=? ORDER BY created_at DESC",
                (rs,n)->{Timestamp value=rs.getTimestamp("created_at",utc());return new Favorite(rs.getLong("favorite_id"),rs.getLong("user_id"),rs.getLong("animal_id"),value==null?null:value.toLocalDateTime());},current);
        if(favorites.isEmpty())return new Favorites(current,0,List.of());
        List<Animal> found=animals.batch(favorites.stream().map(Favorite::animalId).toList());
        Map<Long,Animal> byId=found.stream().collect(Collectors.toMap(Animal::id,Function.identity()));
        List<FavoriteAnimal> content=favorites.stream().map(favorite->{Animal animal=byId.get(favorite.animalId());
            return animal==null?new FavoriteAnimal(favorite.favoriteId(),favorite.userId(),favorite.animalId(),favorite.createdAt(),null,null,null,null,null,null,"DELETED",null,null)
                :new FavoriteAnimal(favorite.favoriteId(),favorite.userId(),favorite.animalId(),favorite.createdAt(),animal.breed(),animal.species(),animal.gender(),animal.age(),animal.imageUrl(),animal.shelterName(),animal.status(),animal.apmsNoticeNo(),animal.specialMark());}).toList();
        return new Favorites(current,content.size(),content);
    });}
    public AnimalPage registered(long current,PageRequest request){return read.execute(tx->{
        List<CurrentUser> users=jdbc.query("SELECT role,care_reg_no FROM pawbridge_user.users WHERE user_id=?",(rs,n)->new CurrentUser(rs.getString("role"),rs.getString("care_reg_no")),current);
        if(users.isEmpty())throw new UserNotFoundException();CurrentUser user=users.getFirst();
        // The source UnauthorizedException is a RuntimeException handled by its generic 500 advice.
        if(!"ROLE_SHELTER".equals(user.role()))throw new IllegalStateException("Shelter membership is required");
        if(user.careRegNo()==null||user.careRegNo().isEmpty())throw new IllegalStateException("Shelter registration is missing");
        Shelter shelter;
        try{shelter=animals.shelter(user.careRegNo());if(shelter==null)throw new IllegalStateException("Shelter response missing");}
        catch(Exception unavailable){throw new IllegalStateException("Shelter lookup failed",unavailable);}
        try{
            if(shelter.id()==null)throw new IllegalStateException("Shelter identity missing");
            AnimalPage result=animals.byShelter(shelter.id(),request);
            if(result==null)throw new IllegalStateException("Animal page missing");return result;
        }catch(Exception unavailable){throw new IllegalStateException("Animal page lookup failed",unavailable);}
    });}
    private boolean exists(long current,long animal){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pawbridge_user.favorites WHERE user_id=? AND animal_id=?)",Boolean.class,current,animal);}
    private void requireUser(long current){if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pawbridge_user.users WHERE user_id=?)",Boolean.class,current))throw new UserNotFoundException();}
    private void event(long current,long animal,String type){
        // FavoriteService and OutboxService intentionally generate distinct UUIDs in the pinned source.
        String payloadId=ids.get().toString();Map<String,Object> payload=Map.of("eventType",type,"eventId",payloadId,"userId",current,"animalId",animal,"timestamp",now.get().toString());
        try{
            String outboxId=ids.get().toString();String json=payloads.writeValueAsString(payload);
            jdbc.update("INSERT INTO pawbridge_user.outbox_events(event_id,aggregate_type,aggregate_id,event_type,topic,payload,created_at) VALUES(?,'Favorite',?,?,'user.favorite.events',CAST(? AS json),?)",
                    outboxId,Long.toString(current),type,json,timestamp(now.get()));
        }catch(JsonProcessingException|RuntimeException unavailable){
            // OutboxService wraps its persistence failure: do not misclassify its UNIQUE error as favorite409.
            throw new IllegalStateException("Outbox event persistence failed",unavailable);
        }
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static SqlValue timestamp(LocalDateTime value){return new SqlValue(){public void setValue(PreparedStatement statement,int index)throws SQLException{statement.setTimestamp(index,Timestamp.valueOf(value),utc());}public void cleanup(){ }};}
    private record CurrentUser(String role,String careRegNo) { }
}
