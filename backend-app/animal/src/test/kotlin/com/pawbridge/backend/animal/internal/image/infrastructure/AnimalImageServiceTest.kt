package com.pawbridge.backend.animal.internal.image.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.util.stream.Stream

class AnimalImageServiceTest {
    private val images = mockk<S3AnimalImages>()
    private val actors = mockk<AnimalCommandActors>()
    private val manager = mockk<PlatformTransactionManager>()
    private val subject = AnimalImageService(images, actors, TransactionTemplate(manager))
    private val photo = MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1, 2, 3))

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_ADMIN", "ROLE_SHELTER"])
    fun `현재 관리자나 보호소 계정일 때__업로드와 삭제를 하면__조회를 커밋한 뒤에야 저장소를 호출한다`(role: String) {
        // given
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns Actor(7L, role, null, true, false)
        every { images.uploadImage(any()) } returns "https://assets.invalid/animals/x.png"
        justRun { images.deleteImage(any()) }

        // when
        val url = subject.uploadImage(7L, photo)
        subject.deleteImage(7L, "https://assets.invalid/products/y.png")

        // then
        assertThat(url).isEqualTo("https://assets.invalid/animals/x.png")
        verifyOrder {
            manager.getTransaction(any())
            actors.find(7L)
            manager.commit(any())
            images.uploadImage(photo)
            manager.getTransaction(any())
            actors.find(7L)
            manager.commit(any())
            images.deleteImage("https://assets.invalid/products/y.png")
        }
        confirmVerified(images, actors)
    }

    @ParameterizedTest
    @MethodSource("refused")
    fun `사용자·삭제됨·대기·불일치·알 수 없는 행위자일 때__업로드와 삭제를 하면__저장소 호출 전에 거부한다`(actor: Actor?) {
        // given
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns actor

        // when
        val upload = assertThatThrownBy { subject.uploadImage(7L, photo) }
        val delete = assertThatThrownBy { subject.deleteImage(7L, "https://assets.invalid/animals/x.png") }

        // then
        upload.isInstanceOf(AnimalImageForbiddenException::class.java)
            .hasMessage("권한이 부족합니다.")
        delete.isInstanceOf(AnimalImageForbiddenException::class.java)
        confirmVerified(images)
    }

    @Test
    fun `검증된 주체가 없을 때__업로드와 삭제를 하면__조회나 저장소 호출 없이 거부한다`() {
        // given

        // when
        val upload = assertThatThrownBy { subject.uploadImage(null, photo) }
        val delete = assertThatThrownBy { subject.deleteImage(null, "x") }

        // then
        upload.isInstanceOf(AnimalImageForbiddenException::class.java)
        delete.isInstanceOf(AnimalImageForbiddenException::class.java)
        confirmVerified(actors, images, manager)
    }

    @Test
    fun `사용자 역할이 원래 요청 한도를 넘는 단일 파일을 올릴 때__업로드하면__역할 거부가 먼저이고 보호소 역할은 크기 초과로 거부한다`() {
        // given
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returnsMany listOf(
            Actor(7L, "ROLE_USER", null, true, false),
            Actor(7L, "ROLE_SHELTER", "C1", true, false),
        )

        // when
        val userUpload = assertThatThrownBy { subject.uploadImage(7L, sized(10L * 1024 * 1024 + 1)) }
        val shelterUpload = assertThatThrownBy { subject.uploadImage(7L, sized(10L * 1024 * 1024 + 1)) }

        // then
        userUpload.isInstanceOf(AnimalImageForbiddenException::class.java)
        shelterUpload.isInstanceOf(AnimalImageTooLargeException::class.java)
            .hasMessage("사진 크기 제한을 초과했습니다")
        confirmVerified(images)
    }

    @Test
    fun `검증된 회원이 여러 파일을 올릴 때__여러 파일 업로드를 하면__역할 조회 없이 요청 순서대로 URL을 돌려준다`() {
        // given
        val second = MockMultipartFile("files", "b.png", "image/png", byteArrayOf(4))

        every { images.uploadImage(photo) } returns "u1"
        every { images.uploadImage(second) } returns "u2"

        // when
        val urls = subject.uploadImages(arrayOf<MultipartFile?>(photo, second))

        // then
        assertThat(urls).containsExactly("u1", "u2")
        verifyOrder {
            images.uploadImage(photo)
            images.uploadImage(second)
        }
        confirmVerified(actors, manager)
    }

    @Test
    fun `파일이 없거나 너무 많을 때__여러 파일 업로드를 하면__원래 메시지로 거부한다`() {
        // given
        val six = arrayOfNulls<MultipartFile>(6)
        for (i in 0 until 6) {
            six[i] = photo
        }

        // when
        val none = assertThatThrownBy { subject.uploadImages(null) }
        val empty = assertThatThrownBy { subject.uploadImages(arrayOfNulls<MultipartFile>(0)) }
        val tooMany = assertThatThrownBy { subject.uploadImages(six) }

        // then
        none.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("업로드할 파일이 없습니다.")
        empty.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("업로드할 파일이 없습니다.")
        tooMany.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("최대 5개의 이미지만 업로드할 수 있습니다.")
        confirmVerified(images, actors, manager)
    }

    @Test
    fun `합계가 원래 요청 한도를 넘을 때__여러 파일 업로드를 하면__개수 검증이나 저장 전에 거부한다`() {
        // given
        val many = arrayOfNulls<MultipartFile>(6)
        for (i in 0 until 6) {
            many[i] = sized(2L * 1024 * 1024)
        }

        // when
        val tooManyLarge = assertThatThrownBy { subject.uploadImages(many) }
        val totalTooLarge = assertThatThrownBy {
            subject.uploadImages(arrayOf<MultipartFile?>(sized(6L * 1024 * 1024), sized(5L * 1024 * 1024)))
        }

        // then
        tooManyLarge.isInstanceOf(AnimalImageTooLargeException::class.java)
        totalTooLarge.isInstanceOf(AnimalImageTooLargeException::class.java)
        confirmVerified(images)
    }

    @Test
    fun `나중 업로드가 실패할 때__여러 파일 업로드를 하면__앞선 객체는 두고 실패를 전파한다`() {
        // given
        val second = MockMultipartFile("files", "b.png", "image/png", byteArrayOf(4))
        val failure = IllegalStateException("synthetic storage failure")

        every { images.uploadImage(photo) } returns "u1"
        every { images.uploadImage(second) } throws failure

        // when
        val thrown = assertThatThrownBy { subject.uploadImages(arrayOf<MultipartFile?>(photo, second)) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { images.uploadImage(photo) }
        verify(exactly = 1) { images.uploadImage(second) }
        confirmVerified(images)
    }

    /** A file whose only meaningful property is its size: a data fake, so no stub is needed to build it. */
    private fun sized(size: Long): MultipartFile {
        return object : MockMultipartFile("file", ByteArray(0)) {
            override fun getSize(): Long = size

            override fun isEmpty(): Boolean = false
        }
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
