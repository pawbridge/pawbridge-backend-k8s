package com.pawbridge.backend.community.internal.post.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdatePostTest {
    private final PostReader reader=mock(PostReader.class);
    private final PostMediaRemover remover=mock(PostMediaRemover.class);
    private final PostMediaUploader uploader=mock(PostMediaUploader.class);
    private final UserNicknameQuery users=mock(UserNicknameQuery.class);
    private final PostModification writer=mock(PostModification.class);
    private final UpdatePost update=new UpdatePost(reader,remover,uploader,users,writer);
    private final LocalDateTime time=LocalDateTime.of(2026,1,1,0,0);
    private final PostSnapshot previous=new PostSnapshot(42,7,"제목","본문","MISSING",List.of("https://fixture.invalid/old"),time,time);

    @BeforeEach void givenExistingOwner(){when(reader.findById(42)).thenReturn(Optional.of(previous));when(users.findNickname(7)).thenReturn(Optional.of("작성자"));}

    @Test void given__omitted_files_and_null_fields__when__updating__then__pass_null_patch_without_media_and_keep_missing_board() {
        when(writer.update(42,7,null,null,null)).thenReturn(previous);
        PostDetails response=update.update(42,7,null,null,List.of());
        assertThat(response.authorNickname()).isEqualTo("작성자");assertThat(response.boardType()).isEqualTo("MISSING");
        verifyNoInteractions(remover,uploader);verify(writer).update(42,7,null,null,null);
    }
    @Test void given__one_empty_file_and_explicit_empty_text__when__updating__then__remove_upload_lookup_and_write_in_order() {
        var file=new PostUpload("empty.png","image/png",0,()->new ByteArrayInputStream(new byte[0]));
        when(uploader.upload(List.of(file))).thenReturn(List.of());
        when(writer.update(42,7,"","",List.of())).thenReturn(new PostSnapshot(42,7,"","","MISSING",List.of(),time,time));
        assertThat(update.update(42,7,"","",List.of(file)).imageUrls()).isEmpty();
        var order=inOrder(reader,remover,uploader,users,writer);
        order.verify(reader).findById(42);order.verify(remover).remove(previous.imageUrls());order.verify(uploader).upload(List.of(file));
        order.verify(users).findNickname(7);order.verify(writer).update(42,7,"","",List.of());
    }
    @Test void given__foreign_author__when__updating__then__deny_before_any_external_or_writer_work() {
        assertThatThrownBy(()->update.update(42,8,"수정",null,List.of())).isInstanceOf(PostNotOwnedException.class);
        verifyNoInteractions(remover,uploader,users,writer);
    }
    @Test void given__missing_post__when__updating__then__404_before_any_external_or_writer_work() {
        when(reader.findById(42)).thenReturn(Optional.empty());
        assertThatThrownBy(()->update.update(42,7,"수정",null,List.of())).isInstanceOf(PostNotFoundException.class);
        verifyNoInteractions(remover,uploader,users,writer);
    }
    @Test void given__upload_failure_after_old_media_attempt__when__updating__then__propagate_without_writer_or_compensation() {
        var file=new PostUpload("a.png","image/png",1,()->new ByteArrayInputStream(new byte[]{1}));
        var failure=new IllegalStateException("synthetic upload failure");when(uploader.upload(List.of(file))).thenThrow(failure);
        assertThatThrownBy(()->update.update(42,7,null,null,List.of(file))).isSameAs(failure);
        verify(remover).remove(previous.imageUrls());verifyNoInteractions(users,writer);verifyNoMoreInteractions(remover);
    }
    @Test void given__late_writer_rejection_after_upload__when__updating__then__propagate_without_retry_or_media_compensation() {
        var file=new PostUpload("a.png","image/png",1,()->new ByteArrayInputStream(new byte[]{1}));
        when(uploader.upload(List.of(file))).thenReturn(List.of("https://fixture.invalid/new"));
        var failure=new PostNotFoundException();when(writer.update(42,7,null,null,List.of("https://fixture.invalid/new"))).thenThrow(failure);
        assertThatThrownBy(()->update.update(42,7,null,null,List.of(file))).isSameAs(failure);
        verify(writer).update(42,7,null,null,List.of("https://fixture.invalid/new"));verify(remover).remove(previous.imageUrls());verifyNoMoreInteractions(remover,writer);
    }
    @Test void given__missing_nickname__when__updating__then__use_legacy_fallback_after_media_before_writer() {
        when(users.findNickname(7)).thenReturn(Optional.empty());when(writer.update(42,7,null,null,null)).thenReturn(previous);
        assertThat(update.update(42,7,null,null,List.of()).authorNickname()).isEqualTo("사용자7");
    }
    @Test void given__empty_nickname__when__updating__then__preserve_legacy_null_nickname() {
        when(users.findNickname(7)).thenReturn(Optional.of(""));when(writer.update(42,7,null,null,null)).thenReturn(previous);
        assertThat(update.update(42,7,null,null,List.of()).authorNickname()).isNull();
    }
    @Test void given__nickname_transport_unavailable__when__updating__then__use_legacy_fallback() {
        when(users.findNickname(7)).thenThrow(new UserNicknameUnavailableException(new IllegalStateException("synthetic")));
        when(writer.update(42,7,null,null,null)).thenReturn(previous);
        assertThat(update.update(42,7,null,null,List.of()).authorNickname()).isEqualTo("사용자7");
    }
}
