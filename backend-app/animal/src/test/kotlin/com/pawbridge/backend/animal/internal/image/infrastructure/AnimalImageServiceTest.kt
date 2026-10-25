package com.pawbridge.backend.animal.internal.image.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.InOrder
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.util.stream.Stream

class AnimalImageServiceTest {
    private val images: S3AnimalImages = mock(S3AnimalImages::class.java)
    private val actors: AnimalCommandActors = mock(AnimalCommandActors::class.java)
    private val manager: PlatformTransactionManager = mock(PlatformTransactionManager::class.java)
    private val subject = AnimalImageService(images, actors, TransactionTemplate(manager))
    private val photo = MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1, 2, 3))

    init {
        `when`(manager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_ADMIN", "ROLE_SHELTER"])
    fun givenCurrentAdminOrShelter__whenUploadingOrDeleting__thenCommitLookupBeforeStorage(role: String) {
        `when`(actors.find(7L)).thenReturn(Actor(7L, role, null, true, false))
        `when`(images.uploadImage(photo)).thenReturn("https://assets.invalid/animals/x.png")

        assertThat(subject.uploadImage(7L, photo)).isEqualTo("https://assets.invalid/animals/x.png")
        subject.deleteImage(7L, "https://assets.invalid/products/y.png")

        val order: InOrder = inOrder(manager, actors, images)
        order.verify(manager).getTransaction(any())
        order.verify(actors).find(7L)
        order.verify(manager).commit(any(TransactionStatus::class.java))
        order.verify(images).uploadImage(photo)
        order.verify(manager).getTransaction(any())
        order.verify(actors).find(7L)
        order.verify(manager).commit(any(TransactionStatus::class.java))
        order.verify(images).deleteImage("https://assets.invalid/products/y.png")
        verifyNoMoreInteractions(images, actors)
    }

    @ParameterizedTest
    @MethodSource("refused")
    fun givenUserDeletedPendingMismatchedOrUnknownActor__whenUploadingOrDeleting__thenForbidBeforeStorage(actor: Actor?) {
        `when`(actors.find(7L)).thenReturn(actor)

        assertThatThrownBy { subject.uploadImage(7L, photo) }
            .isInstanceOf(AnimalImageForbiddenException::class.java)
            .hasMessage("권한이 부족합니다.")
        assertThatThrownBy { subject.deleteImage(7L, "https://assets.invalid/animals/x.png") }
            .isInstanceOf(AnimalImageForbiddenException::class.java)
        verifyNoInteractions(images)
    }

    @Test
    fun givenNoVerifiedPrincipal__whenUploadingOrDeleting__thenForbidWithoutLookupOrStorage() {
        assertThatThrownBy { subject.uploadImage(null, photo) }.isInstanceOf(AnimalImageForbiddenException::class.java)
        assertThatThrownBy { subject.deleteImage(null, "x") }.isInstanceOf(AnimalImageForbiddenException::class.java)
        verifyNoInteractions(actors, images, manager)
    }

    @Test
    fun givenUserRole__whenSingleUploadExceedsOriginalRequestLimit__thenRoleRefusalComesFirst() {
        `when`(actors.find(7L)).thenReturn(Actor(7L, "ROLE_USER", null, true, false))
        assertThatThrownBy { subject.uploadImage(7L, sized(10L * 1024 * 1024 + 1)) }
            .isInstanceOf(AnimalImageForbiddenException::class.java)
        `when`(actors.find(7L)).thenReturn(Actor(7L, "ROLE_SHELTER", "C1", true, false))
        assertThatThrownBy { subject.uploadImage(7L, sized(10L * 1024 * 1024 + 1)) }
            .isInstanceOf(AnimalImageTooLargeException::class.java)
            .hasMessage("사진 크기 제한을 초과했습니다")
        verifyNoInteractions(images)
    }

    @Test
    fun givenMultipleUpload__whenAnyVerifiedMember__thenSkipRoleLookupAndReturnUrlsInRequestOrder() {
        val second = MockMultipartFile("files", "b.png", "image/png", byteArrayOf(4))
        `when`(images.uploadImage(photo)).thenReturn("u1")
        `when`(images.uploadImage(second)).thenReturn("u2")

        assertThat(subject.uploadImages(arrayOf<MultipartFile?>(photo, second))).containsExactly("u1", "u2")

        val order = inOrder(images)
        order.verify(images).uploadImage(photo)
        order.verify(images).uploadImage(second)
        verifyNoInteractions(actors, manager)
    }

    @Test
    fun givenNoOrTooManyFiles__whenMultipleUpload__thenRejectWithOriginalMessages() {
        assertThatThrownBy { subject.uploadImages(null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("업로드할 파일이 없습니다.")
        assertThatThrownBy { subject.uploadImages(arrayOfNulls<MultipartFile>(0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("업로드할 파일이 없습니다.")
        val six = arrayOfNulls<MultipartFile>(6)
        for (i in 0 until 6) {
            six[i] = photo
        }
        assertThatThrownBy { subject.uploadImages(six) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("최대 5개의 이미지만 업로드할 수 있습니다.")
        verifyNoInteractions(images, actors, manager)
    }

    @Test
    fun givenTotalAboveOriginalRequestLimit__whenMultipleUpload__thenRejectBeforeCountValidationOrStorage() {
        val many = arrayOfNulls<MultipartFile>(6)
        for (i in 0 until 6) {
            many[i] = sized(2L * 1024 * 1024)
        }
        assertThatThrownBy { subject.uploadImages(many) }.isInstanceOf(AnimalImageTooLargeException::class.java)
        assertThatThrownBy {
            subject.uploadImages(arrayOf<MultipartFile?>(sized(6L * 1024 * 1024), sized(5L * 1024 * 1024)))
        }.isInstanceOf(AnimalImageTooLargeException::class.java)
        verifyNoInteractions(images)
    }

    @Test
    fun givenLaterUploadFailure__whenMultipleUpload__thenKeepEarlierObjectsAndPropagate() {
        val second = MockMultipartFile("files", "b.png", "image/png", byteArrayOf(4))
        val failure = IllegalStateException("synthetic storage failure")
        `when`(images.uploadImage(photo)).thenReturn("u1")
        `when`(images.uploadImage(second)).thenThrow(failure)

        assertThatThrownBy { subject.uploadImages(arrayOf<MultipartFile?>(photo, second)) }.isSameAs(failure)

        verify(images).uploadImage(photo)
        verify(images).uploadImage(second)
        verifyNoMoreInteractions(images)
    }

    private fun sized(size: Long): MultipartFile {
        val file = mock(MultipartFile::class.java)
        `when`(file.size).thenReturn(size)
        return file
    }

    companion object {
        @JvmStatic
        fun refused(): Stream<Actor?> = Stream.of(
            Actor(7L, "ROLE_USER", null, true, false),
            Actor(7L, "ROLE_ADMIN", null, false, true),
            Actor(7L, "ROLE_SHELTER", "C1", false, false),
            Actor(8L, "ROLE_ADMIN", null, true, false),
            Actor(7L, null, null, true, false),
            null,
        )
    }
}
