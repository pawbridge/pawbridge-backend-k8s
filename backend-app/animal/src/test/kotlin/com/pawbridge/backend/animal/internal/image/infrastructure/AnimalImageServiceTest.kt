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
    fun `현재 관리자나 보호소 계정일 때__업로드하면__조회를 커밋한 뒤에야 저장소를 호출한다`(role: String) {
        // given
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns Actor(7L, role, null, true, false)
        every { images.uploadImage(any()) } returns "https://assets.invalid/animals/x.png"

        // when
        val url = subject.uploadImage(7L, photo)

        // then
        assertThat(url).isEqualTo("https://assets.invalid/animals/x.png")
        verifyOrder {
            manager.getTransaction(any())
            actors.find(7L)
            manager.commit(any())
            images.uploadImage(photo)
        }
        confirmVerified(images, actors)
    }

    @ParameterizedTest
    @MethodSource("refused")
    fun `사용자·삭제됨·대기·불일치·알 수 없는 행위자일 때__업로드하면__저장소 호출 전에 거부한다`(actor: Actor?) {
        // given
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns actor

        // when
        val upload = assertThatThrownBy { subject.uploadImage(7L, photo) }
        val multiple = assertThatThrownBy { subject.uploadImages(7L, arrayOf<MultipartFile?>(photo)) }

        // then
        upload.isInstanceOf(AnimalImageForbiddenException::class.java)
            .hasMessage("권한이 부족합니다.")
        multiple.isInstanceOf(AnimalImageForbiddenException::class.java)
        confirmVerified(images)
    }

    @Test
    fun `검증된 주체가 없을 때__업로드하면__조회나 저장소 호출 없이 거부한다`() {
        // given

        // when
        val upload = assertThatThrownBy { subject.uploadImage(null, photo) }
        val multiple = assertThatThrownBy { subject.uploadImages(null, arrayOf<MultipartFile?>(photo)) }

        // then
        upload.isInstanceOf(AnimalImageForbiddenException::class.java)
        multiple.isInstanceOf(AnimalImageForbiddenException::class.java)
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
    fun `보호소 계정이 여러 파일을 올릴 때__여러 파일 업로드를 하면__조회를 커밋한 뒤 요청 순서대로 저장한다`() {
        // given
        val second = MockMultipartFile("files", "b.png", "image/png", byteArrayOf(4))

        givenActor(Actor(7L, "ROLE_SHELTER", "C1", true, false))
        every { images.uploadImage(photo) } returns "u1"
        every { images.uploadImage(second) } returns "u2"

        // when
        val urls = subject.uploadImages(7L, arrayOf<MultipartFile?>(photo, second))

        // then
        assertThat(urls).containsExactly("u1", "u2")
        verifyOrder {
            manager.getTransaction(any())
            actors.find(7L)
            manager.commit(any())
            images.uploadImage(photo)
            images.uploadImage(second)
        }
        confirmVerified(images, actors)
    }

    @Test
    fun `파일이 없거나 너무 많을 때__여러 파일 업로드를 하면__원래 메시지로 거부한다`() {
        // given
        val six = arrayOfNulls<MultipartFile>(6)
        for (i in 0 until 6) {
            six[i] = photo
        }

        givenActor(Actor(7L, "ROLE_SHELTER", "C1", true, false))

        // when
        val none = assertThatThrownBy { subject.uploadImages(7L, null) }
        val empty = assertThatThrownBy { subject.uploadImages(7L, arrayOfNulls<MultipartFile>(0)) }
        val tooMany = assertThatThrownBy { subject.uploadImages(7L, six) }

        // then
        none.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("업로드할 파일이 없습니다.")
        empty.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("업로드할 파일이 없습니다.")
        tooMany.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("최대 5개의 이미지만 업로드할 수 있습니다.")
        confirmVerified(images)
    }

    @Test
    fun `장당 10MB 이하이고 합계가 10MB를 넘을 때__여러 파일 업로드를 하면__모두 저장한다`() {
        // given
        givenActor(Actor(7L, "ROLE_ADMIN", null, true, false))
        every { images.uploadImage(any()) } returnsMany listOf("u1", "u2", "u3", "u4", "u5")
        val five = Array<MultipartFile?>(5) { sized(10L * 1024 * 1024) }

        // when
        val urls = subject.uploadImages(7L, five)

        // then
        assertThat(urls).containsExactly("u1", "u2", "u3", "u4", "u5")
    }

    @Test
    fun `한 장이 10MB를 넘거나 합계가 55MB를 넘을 때__여러 파일 업로드를 하면__개수 검증이나 저장 전에 거부한다`() {
        // given
        val oneTooLarge = arrayOf<MultipartFile?>(sized(1024), sized(10L * 1024 * 1024 + 1))
        val sixFull = Array<MultipartFile?>(6) { sized(10L * 1024 * 1024) }

        givenActor(Actor(7L, "ROLE_SHELTER", "C1", true, false))

        // when
        val fileTooLarge = assertThatThrownBy { subject.uploadImages(7L, oneTooLarge) }
        val totalTooLarge = assertThatThrownBy { subject.uploadImages(7L, sixFull) }

        // then
        fileTooLarge.isInstanceOf(AnimalImageTooLargeException::class.java)
        totalTooLarge.isInstanceOf(AnimalImageTooLargeException::class.java)
        confirmVerified(images)
    }

    @Test
    fun `나중 업로드가 실패할 때__여러 파일 업로드를 하면__앞선 객체는 두고 실패를 전파한다`() {
        // given
        val second = MockMultipartFile("files", "b.png", "image/png", byteArrayOf(4))
        val failure = IllegalStateException("synthetic storage failure")

        givenActor(Actor(7L, "ROLE_SHELTER", "C1", true, false))
        every { images.uploadImage(photo) } returns "u1"
        every { images.uploadImage(second) } throws failure

        // when
        val thrown = assertThatThrownBy { subject.uploadImages(7L, arrayOf<MultipartFile?>(photo, second)) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { images.uploadImage(photo) }
        verify(exactly = 1) { images.uploadImage(second) }
        confirmVerified(images)
    }

    private fun givenActor(actor: Actor) {
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns actor
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
