package com.pawbridge.backend.community.internal.report.web;

import com.pawbridge.backend.community.internal.report.application.AnimalReports;
import com.pawbridge.backend.community.internal.report.application.AnimalReports.*;
import com.pawbridge.backend.community.internal.post.application.PostUpload;
import com.pawbridge.backend.community.internal.post.web.ApiResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/reports")
public final class AnimalReportController {
    private final AnimalReports reports;
    public AnimalReportController(AnimalReports reports){this.reports=reports;}
    @GetMapping public ApiResponse<Page<Details>> list(
            @RequestParam(name="page",defaultValue="0") int page,
            @RequestParam(name="size",defaultValue="12") int size,
            @RequestParam(name="kind",required=false) Kind kind,
            @RequestParam(name="keyword",required=false) String keyword,
            @RequestParam(name="province",required=false) String province,
            @RequestParam(name="district",required=false) String district,
            @RequestParam(name="animalType",required=false) AnimalType animalType,
            @RequestParam(name="from",required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name="to",required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to){
        if(page<0 || size<1 || size>50 || (keyword!=null && keyword.length()>100))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"페이지 범위를 확인해 주세요.");
        Slice result=reports.list(new Filter(page,size,kind,keyword,province,district,animalType,from,to));
        return ok(new PageImpl<>(result.content(),PageRequest.of(page,size,
                Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("reportId"))),result.total()));
    }
    @GetMapping("/{reportId}") public ApiResponse<Details> get(@PathVariable("reportId") long id){return ok(reports.get(id));}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<Details> create(
            @RequestPart("report") Draft request,@RequestPart(value="photos",required=false) MultipartFile[] photos,
            @AuthenticationPrincipal Long userId){return ok(reports.create(request,files(photos),userId));}
    @PutMapping(value="/{reportId}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<Details> update(
            @PathVariable("reportId") long id,@RequestPart("report") Draft request,
            @RequestPart(value="photos",required=false) MultipartFile[] photos,@AuthenticationPrincipal Long userId){
        return ok(reports.update(id,request,files(photos),userId));
    }
    @DeleteMapping("/{reportId}") public ApiResponse<Void> delete(@PathVariable("reportId") long id,@AuthenticationPrincipal Long userId){
        reports.delete(id,userId);return new ApiResponse<>(200,"제보가 삭제되었습니다.",null);
    }
    private static <T> ApiResponse<T> ok(T data){return new ApiResponse<>(200,"요청이 성공적으로 처리되었습니다.",data);}
    private static List<PostUpload> files(MultipartFile[] files){
        return files==null?null:Arrays.stream(files).map(f->f==null?null:
                new PostUpload(f.getOriginalFilename(),f.getContentType(),f.getSize(),f::getInputStream)).toList();
    }
}
