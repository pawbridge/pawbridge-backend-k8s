package com.pawbridge.backend.animal.internal.image.infrastructure;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalImageServiceTest {
    private final S3AnimalImages images=mock(S3AnimalImages.class);
    private final AnimalCommandActors actors=mock(AnimalCommandActors.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final AnimalImageService subject=new AnimalImageService(images,actors,new TransactionTemplate(manager));
    private final MockMultipartFile photo=new MockMultipartFile("file","a.png","image/png",new byte[]{1,2,3});

    AnimalImageServiceTest(){when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());}

    @ParameterizedTest @ValueSource(strings={"ROLE_ADMIN","ROLE_SHELTER"})
    void givenCurrentAdminOrShelter__whenUploadingOrDeleting__thenCommitLookupBeforeStorage(String role) {
        when(actors.find(7L)).thenReturn(new Actor(7L,role,null,true,false));when(images.uploadImage(photo)).thenReturn("https://assets.invalid/animals/x.png");
        assertThat(subject.uploadImage(7L,photo)).isEqualTo("https://assets.invalid/animals/x.png");
        subject.deleteImage(7L,"https://assets.invalid/products/y.png");
        InOrder order=inOrder(manager,actors,images);
        order.verify(manager).getTransaction(any());order.verify(actors).find(7L);order.verify(manager).commit(any(TransactionStatus.class));order.verify(images).uploadImage(photo);
        order.verify(manager).getTransaction(any());order.verify(actors).find(7L);order.verify(manager).commit(any(TransactionStatus.class));
        order.verify(images).deleteImage("https://assets.invalid/products/y.png");verifyNoMoreInteractions(images,actors);
    }

    static Stream<Actor> refused(){return Stream.of(new Actor(7L,"ROLE_USER",null,true,false),new Actor(7L,"ROLE_ADMIN",null,false,true),
            new Actor(7L,"ROLE_SHELTER","C1",false,false),new Actor(8L,"ROLE_ADMIN",null,true,false),new Actor(7L,null,null,true,false),null);}

    @ParameterizedTest @MethodSource("refused")
    void givenUserDeletedPendingMismatchedOrUnknownActor__whenUploadingOrDeleting__thenForbidBeforeStorage(Actor actor) {
        when(actors.find(7L)).thenReturn(actor);
        assertThatThrownBy(()->subject.uploadImage(7L,photo)).isInstanceOf(AnimalImageForbiddenException.class).hasMessage("권한이 부족합니다.");
        assertThatThrownBy(()->subject.deleteImage(7L,"https://assets.invalid/animals/x.png")).isInstanceOf(AnimalImageForbiddenException.class);
        verifyNoInteractions(images);
    }

    @Test void givenNoVerifiedPrincipal__whenUploadingOrDeleting__thenForbidWithoutLookupOrStorage() {
        assertThatThrownBy(()->subject.uploadImage(null,photo)).isInstanceOf(AnimalImageForbiddenException.class);
        assertThatThrownBy(()->subject.deleteImage(null,"x")).isInstanceOf(AnimalImageForbiddenException.class);
        verifyNoInteractions(actors,images,manager);
    }

    @Test void givenUserRole__whenSingleUploadExceedsOriginalRequestLimit__thenRoleRefusalComesFirst() {
        when(actors.find(7L)).thenReturn(new Actor(7L,"ROLE_USER",null,true,false));
        assertThatThrownBy(()->subject.uploadImage(7L,sized(10L*1024*1024+1))).isInstanceOf(AnimalImageForbiddenException.class);
        when(actors.find(7L)).thenReturn(new Actor(7L,"ROLE_SHELTER","C1",true,false));
        assertThatThrownBy(()->subject.uploadImage(7L,sized(10L*1024*1024+1))).isInstanceOf(AnimalImageTooLargeException.class).hasMessage("사진 크기 제한을 초과했습니다");
        verifyNoInteractions(images);
    }

    @Test void givenMultipleUpload__whenAnyVerifiedMember__thenSkipRoleLookupAndReturnUrlsInRequestOrder() {
        var second=new MockMultipartFile("files","b.png","image/png",new byte[]{4});
        when(images.uploadImage(photo)).thenReturn("u1");when(images.uploadImage(second)).thenReturn("u2");
        assertThat(subject.uploadImages(new MultipartFile[]{photo,second})).containsExactly("u1","u2");
        InOrder order=inOrder(images);order.verify(images).uploadImage(photo);order.verify(images).uploadImage(second);
        verifyNoInteractions(actors,manager);
    }

    @Test void givenNoOrTooManyFiles__whenMultipleUpload__thenRejectWithOriginalMessages() {
        assertThatThrownBy(()->subject.uploadImages(null)).isInstanceOf(IllegalArgumentException.class).hasMessage("업로드할 파일이 없습니다.");
        assertThatThrownBy(()->subject.uploadImages(new MultipartFile[0])).isInstanceOf(IllegalArgumentException.class).hasMessage("업로드할 파일이 없습니다.");
        var six=new MultipartFile[6];for(int i=0;i<6;i++)six[i]=photo;
        assertThatThrownBy(()->subject.uploadImages(six)).isInstanceOf(IllegalArgumentException.class).hasMessage("최대 5개의 이미지만 업로드할 수 있습니다.");
        verifyNoInteractions(images,actors,manager);
    }

    @Test void givenTotalAboveOriginalRequestLimit__whenMultipleUpload__thenRejectBeforeCountValidationOrStorage() {
        var many=new MultipartFile[6];for(int i=0;i<6;i++)many[i]=sized(2L*1024*1024);
        assertThatThrownBy(()->subject.uploadImages(many)).isInstanceOf(AnimalImageTooLargeException.class);
        assertThatThrownBy(()->subject.uploadImages(new MultipartFile[]{sized(6L*1024*1024),sized(5L*1024*1024)})).isInstanceOf(AnimalImageTooLargeException.class);
        verifyNoInteractions(images);
    }

    @Test void givenLaterUploadFailure__whenMultipleUpload__thenKeepEarlierObjectsAndPropagate() {
        var second=new MockMultipartFile("files","b.png","image/png",new byte[]{4});var failure=new IllegalStateException("synthetic storage failure");
        when(images.uploadImage(photo)).thenReturn("u1");when(images.uploadImage(second)).thenThrow(failure);
        assertThatThrownBy(()->subject.uploadImages(new MultipartFile[]{photo,second})).isSameAs(failure);
        verify(images).uploadImage(photo);verify(images).uploadImage(second);verifyNoMoreInteractions(images);
    }

    private static MultipartFile sized(long size){
        MultipartFile file=mock(MultipartFile.class);when(file.getSize()).thenReturn(size);return file;
    }

}
