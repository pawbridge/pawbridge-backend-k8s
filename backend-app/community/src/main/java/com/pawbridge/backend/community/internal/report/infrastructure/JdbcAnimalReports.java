package com.pawbridge.backend.community.internal.report.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.report.application.AnimalReports;
import com.pawbridge.backend.community.internal.post.application.PostUpload;
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader;
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

/** Minimal JDBC translation of the existing JPA report workflow and visible-row predicates. */
public final class JdbcAnimalReports implements AnimalReports {
    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    private static final Set<String> PROVINCES = Set.of("서울특별시", "부산광역시", "대구광역시",
            "인천광역시", "광주광역시", "대전광역시", "울산광역시", "세종특별자치시", "경기도",
            "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도",
            "경상북도", "경상남도", "제주특별자치도");
    private static final String TABLE = "pawbridge_community.animal_reports";
    private static final String COLUMNS = "report_id,author_id,report_kind,description,image_urls,occurred_on,"
            + "approximate_time,region,landmark,species,animal_name,coat_color,animal_size,"
            + "distinguishing_features,direction,created_at,updated_at,province,district,animal_type";
    private static final TypeReference<List<String>> IMAGES = new TypeReference<>() {};
    private final JdbcOperations reader;
    private final JdbcOperations writer;
    private final TransactionOperations readTransaction;
    private final TransactionOperations writeTransaction;
    private final UserNicknameQuery users;
    private final PostMediaUploader uploads;
    private final PostMediaRemover media;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcAnimalReports(JdbcOperations reader, JdbcOperations writer,
            TransactionOperations readTransaction, TransactionOperations writeTransaction,
            UserNicknameQuery users, PostMediaUploader uploads, PostMediaRemover media,
            ObjectMapper mapper, Clock clock) {
        this.reader=reader; this.writer=writer; this.readTransaction=readTransaction;
        this.writeTransaction=writeTransaction; this.users=users; this.uploads=uploads;
        this.media=media; this.mapper=mapper; this.clock=clock;
    }

    @Override public Slice list(Filter f) {
        // Controller pagination checks precede the original service classification/date checks.
        if (f.page()<0 || f.size()<1 || f.size()>50 || (f.keyword()!=null && f.keyword().length()>100))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"페이지 범위를 확인해 주세요.");
        validateClassification(f.province(),f.district());
        LocalDate today=today();
        if ((f.from()!=null && f.from().isAfter(today)) || (f.to()!=null && f.to().isAfter(today))
                || (f.from()!=null && f.to()!=null && f.from().isAfter(f.to())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"실종·목격 날짜 범위를 확인해 주세요.");
        String keyword=f.keyword()==null || f.keyword().isBlank()?"":f.keyword().trim().toLowerCase(Locale.ROOT);
        var where=new StringBuilder(" WHERE deleted_at IS NULL"); var args=new ArrayList<Object>();
        equal(where,args,"report_kind",f.kind()==null?null:f.kind().name());
        equal(where,args,"province",optional(f.province())); equal(where,args,"district",optional(f.district()));
        equal(where,args,"animal_type",f.animalType()==null?null:f.animalType().name());
        if(f.from()!=null){where.append(" AND occurred_on >= ?");args.add(f.from());}
        if(f.to()!=null){where.append(" AND occurred_on <= ?");args.add(f.to());}
        if(!keyword.isEmpty()) {
            where.append(" AND (position(? in lower(description)) > 0 OR position(? in lower(region)) > 0"
                    +" OR position(? in lower(species)) > 0 OR position(? in lower(animal_name)) > 0"
                    +" OR position(? in lower(distinguishing_features)) > 0)");
            for(int i=0;i<5;i++)args.add(keyword);
        }
        long offset=(long)f.page()*f.size();
        Slice raw=readTransaction.execute(status->{
            if(offset>Integer.MAX_VALUE)throw new org.springframework.dao.InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)");
            var pageArgs=new ArrayList<>(args);pageArgs.add(f.size());pageArgs.add(offset);
            List<Details> content=reader.query("SELECT "+COLUMNS+" FROM "+TABLE+where
                    +" ORDER BY created_at DESC, report_id DESC LIMIT ? OFFSET ?",this::row,pageArgs.toArray());
            // Spring Data avoids a count when a partial nonempty page already proves the total.
            long total;
            if(content.size()<f.size() && (f.page()==0 || !content.isEmpty())) total=offset+content.size();
            else total=Objects.requireNonNull(reader.queryForObject("SELECT count(*) FROM "+TABLE+where,Long.class,args.toArray()));
            return new Slice(content,total);
        });
        // Keep the User call independent of the Community reader transaction, as in the HTTP boundary.
        return new Slice(raw.content().stream().map(this::nickname).toList(),raw.total());
    }
    private static void equal(StringBuilder sql,List<Object> args,String column,Object value){
        if(value!=null){sql.append(" AND ").append(column).append(" = ?");args.add(value);}
    }

    @Override public Details get(long id) {
        return nickname(readTransaction.execute(status->find(reader,id)));
    }

    // The User nickname read runs after the writer transaction: on the shared pool a failing read inside
    // it would mark the report transaction rollback-only instead of falling back to the placeholder name.
    @Override public Details create(Draft request,List<PostUpload> photos,Long authorId) {
        return nickname(writeTransaction.execute(status->{
            validate(request,photos);
            if(authorId==null || authorId<=0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            List<String> images=uploads.upload(photos);
            try {
                Draft next=normalized(request,optional(request.province()),optional(request.district()),request.animalType());
                LocalDateTime now=now();
                Long id=writer.queryForObject("INSERT INTO "+TABLE+" (author_id,report_kind,description,image_urls,occurred_on,"
                        +"approximate_time,region,landmark,species,animal_name,coat_color,animal_size,distinguishing_features,"
                        +"direction,province,district,animal_type,created_at,updated_at) "
                        +"VALUES (?,?,?,CAST(? AS json),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING report_id",Long.class,
                        authorId,next.kind().name(),next.description(),json(images),next.occurredOn(),next.approximateTime(),
                        next.region(),next.landmark(),next.species(),next.animalName(),next.coatColor(),next.animalSize(),
                        next.distinguishingFeatures(),next.direction(),next.province(),next.district(),
                        next.animalType()==null?null:next.animalType().name(),now,now);
                if(id==null)throw new IllegalStateException("Report insert returned no identity");
                return details(id,authorId,next,images,now,now);
            } catch(RuntimeException failure) {
                // Original saveAndFlush/body failures clean uploads; proxy commit failures do not.
                media.remove(images); throw failure;
            }
        }));
    }

    @Override public Details update(long id,Draft request,List<PostUpload> photos,Long authorId) {
        return nickname(writeTransaction.execute(status->{
            validate(request,photos);
            if(photos!=null && !photos.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "등록 후 사진 교체는 아직 지원하지 않습니다.");
            Details current=find(writer,id); requireAuthor(current,authorId);
            if(current.kind()!=request.kind())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"제보 종류는 변경할 수 없습니다.");
            boolean classify=request.province()!=null || request.district()!=null || request.animalType()!=null;
            Draft next=normalized(request,classify?optional(request.province()):current.province(),
                    classify?optional(request.district()):current.district(),classify?request.animalType():current.animalType());
            Details result=details(id,current.authorId(),next,current.imageUrls(),current.createdAt(),current.updatedAt());
            if(!result.equals(current))writer.update("UPDATE "+TABLE+" SET description=?,occurred_on=?,approximate_time=?,region=?,"
                    +"landmark=?,species=?,animal_name=?,coat_color=?,animal_size=?,distinguishing_features=?,direction=?,"
                    +"province=?,district=?,animal_type=?,updated_at=? WHERE report_id=?",
                    next.description(),next.occurredOn(),next.approximateTime(),next.region(),next.landmark(),next.species(),
                    next.animalName(),next.coatColor(),next.animalSize(),next.distinguishingFeatures(),next.direction(),
                    next.province(),next.district(),next.animalType()==null?null:next.animalType().name(),now(),id);
            // JPA auditing flush occurs after the original response is built: retain its prior updatedAt.
            return result;
        }));
    }

    @Override public void delete(long id,Long authorId) {
        writeTransaction.executeWithoutResult(status->{
            Details current=find(writer,id);requireAuthor(current,authorId);
            writer.update("UPDATE "+TABLE+" SET deleted_at=?,updated_at=? WHERE report_id=?",now(),now(),id);
        });
    }
    private static void requireAuthor(Details current,Long author){
        if(author==null || !current.authorId().equals(author))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    private Details find(JdbcOperations jdbc,long id){
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM "+TABLE+" WHERE report_id=? AND deleted_at IS NULL",this::row,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private Details row(ResultSet rs,int index)throws SQLException {
        try {
            Draft draft=new Draft(Kind.valueOf(rs.getString("report_kind")),rs.getObject("occurred_on",LocalDate.class),
                    rs.getString("approximate_time"),rs.getString("region"),rs.getString("landmark"),rs.getString("species"),
                    rs.getString("animal_name"),rs.getString("coat_color"),rs.getString("animal_size"),
                    rs.getString("distinguishing_features"),rs.getString("direction"),rs.getString("description"),
                    rs.getString("province"),rs.getString("district"),rs.getString("animal_type")==null?null:AnimalType.valueOf(rs.getString("animal_type")));
            return details(rs.getLong("report_id"),rs.getLong("author_id"),draft,mapper.readValue(rs.getString("image_urls"),IMAGES),
                    rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class));
        }catch(IOException failure){throw new IllegalStateException("Stored report media cannot be decoded");}
    }
    private Details nickname(Details d){
        String name;try{name=users.findNickname(d.authorId()).orElse("사용자"+d.authorId());}
        catch(RuntimeException unavailable){name="사용자"+d.authorId();}
        return new Details(d.reportId(),d.authorId(),name,d.title(),d.description(),d.imageUrls(),d.kind(),d.occurredOn(),
                d.approximateTime(),d.region(),d.landmark(),d.species(),d.animalName(),d.coatColor(),d.animalSize(),
                d.distinguishingFeatures(),d.direction(),d.createdAt(),d.updatedAt(),d.province(),d.district(),d.animalType());
    }
    private static Details details(long id,long author,Draft d,List<String> images,LocalDateTime created,LocalDateTime updated){
        String label=d.kind()==Kind.MISSING?"실종":"목격 제보";
        return new Details(id,author,null,label+" · "+d.region()+" · "+d.species(),d.description(),List.copyOf(images),
                d.kind(),d.occurredOn(),d.approximateTime(),d.region(),d.landmark(),d.species(),d.animalName(),d.coatColor(),
                d.animalSize(),d.distinguishingFeatures(),d.direction(),created,updated,d.province(),d.district(),d.animalType());
    }
    private static Draft normalized(Draft d,String province,String district,AnimalType type){
        return new Draft(d.kind(),d.occurredOn(),optional(d.approximateTime()),d.region().trim(),optional(d.landmark()),
                d.species().trim(),optional(d.animalName()),optional(d.coatColor()),optional(d.animalSize()),
                optional(d.distinguishingFeatures()),optional(d.direction()),d.description().trim(),province,district,type);
    }
    private String json(List<String> values){
        try{return mapper.writeValueAsString(values);}catch(IOException failure){throw new IllegalStateException("Report media cannot be encoded");}
    }
    private LocalDate today(){return LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Seoul"));}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneId.systemDefault());}
    private static void validateClassification(String province, String district) {
        String normalizedProvince = optional(province);
        String normalizedDistrict = optional(district);
        if ((normalizedProvince != null && !PROVINCES.contains(normalizedProvince))
                || tooLong(district, 40) || (normalizedDistrict != null && normalizedProvince == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시·도와 시·군·구 선택을 확인해 주세요.");
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validate(Draft request, List<PostUpload> photos) {
        if (request == null || request.kind() == null || request.occurredOn() == null
                || request.occurredOn().isAfter(today())
                || blankOrTooLong(request.region(), 120) || blankOrTooLong(request.species(), 40)
                || blankOrTooLong(request.description(), 10000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "필수 제보 정보를 확인해 주세요.");
        }
        validateClassification(request.province(), request.district());
        if (tooLong(request.approximateTime(), 40) || tooLong(request.landmark(), 200)
                || tooLong(request.animalName(), 80) || tooLong(request.coatColor(), 100)
                || tooLong(request.animalSize(), 40)
                || tooLong(request.distinguishingFeatures(), 500) || tooLong(request.direction(), 200)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제보 내용의 글자 수를 확인해 주세요.");
        }
        if (photos == null) return;
        if (photos.size() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 최대 5장까지 첨부할 수 있습니다.");
        }
        for (PostUpload photo : photos) validatePhoto(photo);
    }

    private static boolean blankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.trim().length() > max;
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.trim().length() > max;
    }

    private static void validatePhoto(PostUpload photo) {
        if (photo == null || photo.size() == 0 || photo.size() > MAX_PHOTO_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 각 10MB 이하로 첨부해 주세요.");
        }
        String type = photo.contentType();
        String name = photo.originalFilename();
        if (type == null || name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 형식을 확인해 주세요.");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean extensionMatches = switch (type.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            case "image/webp" -> lower.endsWith(".webp");
            default -> false;
        };
        if (!extensionMatches) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JPG, PNG, WebP 사진만 첨부할 수 있습니다.");
        }
        try (var stream = photo.content().open()) {
            byte[] header = stream.readNBytes(12);
            boolean jpeg = type.equalsIgnoreCase("image/jpeg") && header.length >= 3
                    && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
            boolean png = type.equalsIgnoreCase("image/png") && header.length >= 8
                    && (header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                    && header[4] == 13 && header[5] == 10 && (header[6] & 0xff) == 0x1a && header[7] == 10;
            boolean webp = type.equalsIgnoreCase("image/webp") && header.length >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            if (!jpeg && !png && !webp) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 파일의 실제 형식을 확인해 주세요.");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 읽을 수 없습니다.", e);
        }
    }
}
