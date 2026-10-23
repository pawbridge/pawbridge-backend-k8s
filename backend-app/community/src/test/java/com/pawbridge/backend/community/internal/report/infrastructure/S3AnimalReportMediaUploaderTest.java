package com.pawbridge.backend.community.internal.report.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.pawbridge.backend.community.internal.post.application.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3AnimalReportMediaUploaderTest {
    @Test void given__second_upload_failure__when__uploading_reports__then__delete_only_prior_report_urls_and_keep_report_folder() {
        S3Client s3=mock(S3Client.class);PostMediaRemover remover=mock(PostMediaRemover.class);List<String> keys=new ArrayList<>();
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(c->{
            PutObjectRequest request=c.getArgument(0);keys.add(request.key());if(keys.size()==2)throw new IllegalStateException("private-storage");
            RequestBody body=c.getArgument(1);assertThat(body.contentStreamProvider().newStream().readAllBytes()).containsExactly(1,2,3);
            assertThat(body.contentStreamProvider().newStream().readAllBytes()).containsExactly(1,2,3);return PutObjectResponse.builder().build();});
        var uploads=new S3AnimalReportMediaUploader(s3,"fixture-bucket","https://fixture.invalid/","owned/",remover);
        var photo=new PostUpload("one.png","image/png",3,()->new ByteArrayInputStream(new byte[]{1,2,3}));
        assertThatThrownBy(()->uploads.upload(List.of(photo,photo))).isInstanceOf(RuntimeException.class).hasMessage("파일 업로드 중 오류가 발생했습니다");
        assertThat(keys).hasSize(2).allSatisfy(key->assertThat(key).startsWith("owned/reports/images/").endsWith(".png"));
        verify(remover).remove(List.of("https://fixture.invalid/"+keys.getFirst()));
    }
    @Test void given__no_photos__when__uploading__then__no_external_io(){
        S3Client s3=mock(S3Client.class);PostMediaRemover remover=mock(PostMediaRemover.class);
        var uploads=new S3AnimalReportMediaUploader(s3,"fixture","https://fixture.invalid","",remover);
        assertThat(uploads.upload(null)).isEmpty();assertThat(uploads.upload(List.of())).isEmpty();verifyNoInteractions(s3,remover);
    }
    @Test void given__empty_prefix__when__uploading_report_photo__then__key_is_at_bucket_root_and_url_matches_legacy_format() {
        S3Client s3=mock(S3Client.class);
        PostMediaRemover remover=mock(PostMediaRemover.class);
        List<String> keys=new ArrayList<>();
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call->{
            PutObjectRequest request=call.getArgument(0);
            keys.add(request.key());
            return PutObjectResponse.builder().build();
        });
        var uploads=new S3AnimalReportMediaUploader(s3,"fixture-bucket","https://fixture.invalid///","",remover);
        var photo=new PostUpload("one.png","image/png",3,()->new ByteArrayInputStream(new byte[]{1,2,3}));
        List<String> urls=uploads.upload(List.of(photo));
        assertThat(keys).singleElement().asString().matches("reports/images/[0-9a-f-]{36}\\.png");
        assertThat(urls).containsExactly("https://fixture.invalid/"+keys.getFirst());
        verifyNoInteractions(remover);
    }
}
