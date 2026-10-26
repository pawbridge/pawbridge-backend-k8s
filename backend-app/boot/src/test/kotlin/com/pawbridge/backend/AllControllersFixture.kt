package com.pawbridge.backend

import com.pawbridge.backend.community.internal.comment.web.CommentCreateController
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentReadController
import com.pawbridge.backend.community.internal.comment.web.CommentReadExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteDeletionController
import com.pawbridge.backend.community.internal.note.web.NoteDeletionExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteController
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationController
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteStreamController
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.AdminPostDeleteController
import com.pawbridge.backend.community.internal.post.web.AdminPostReadController
import com.pawbridge.backend.community.internal.post.web.AdminPostReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.AdminPostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostDeleteController
import com.pawbridge.backend.community.internal.post.web.PostDeleteExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostReadController
import com.pawbridge.backend.community.internal.post.web.PostReadExceptionHandler
import com.pawbridge.backend.community.internal.post.web.PostUpdateController
import com.pawbridge.backend.community.internal.post.web.PostUpdateExceptionHandler
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateController
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesController
import com.pawbridge.backend.user.internal.animalactivity.web.UserAnimalActivitiesExceptionHandler
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockkClass
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import java.time.Clock
import java.util.function.Supplier
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * Every controller and exception handler the table tests exercise, assembled once the way the application
 * assembles them. Each collaborator behind a controller is one shared relaxed mock per type, so a test can
 * stub it with [RelaxedBeans.mock] and confirm that a rejected request never reached it.
 */
@Configuration(proxyBeanMethods = false)
@Import(AllControllers.Registrar::class)
class AllControllers {
    class Registrar : ImportBeanDefinitionRegistrar {
        override fun registerBeanDefinitions(metadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
            (CONTROLLERS + HANDLERS).forEach { type ->
                val definition = GenericBeanDefinition()
                definition.setBeanClass(type.java)
                definition.instanceSupplier = Supplier { RelaxedBeans.create(type) }
                registry.registerBeanDefinition(type.java.name, definition)
            }
        }
    }

    companion object {
        val CONTROLLERS: List<KClass<*>> = listOf(
            AdminPostReadController::class, AdminPostUpdateController::class, AdminPostDeleteController::class,
            PostReadController::class, PostDeleteController::class, PostUpdateController::class,
            AdminUserDeletionController::class, AdminUserDetailController::class, AdminUserUpdateController::class,
            CommentCreateController::class, CommentReadController::class, CommentDeleteController::class,
            CommentUpdateController::class, FavoriteStatusController::class, UserProfileController::class,
            NoteBlockCreateController::class, NoteBlockRemoveController::class, NoteBlockStatusController::class,
            NoteBlockListController::class, NoteDeletionController::class, NoteFavoriteController::class,
            NoteReadMutationController::class, NoteStreamController::class, NoteRecipientController::class,
            NoteNotificationsController::class, PrivateNoteReadController::class, TotalUserCountController::class,
            UserAnimalActivitiesController::class, MockOrderPaymentController::class,
        )

        val HANDLERS: List<KClass<*>> = listOf(
            AdminPostReadExceptionHandler::class, PostReadExceptionHandler::class, PostDeleteExceptionHandler::class,
            PostUpdateExceptionHandler::class, AdminUserDeletionExceptionHandler::class,
            UserProfileExceptionHandler::class, AdminUserUpdateExceptionHandler::class,
            CommentCreateExceptionHandler::class, CommentReadExceptionHandler::class,
            CommentDeleteExceptionHandler::class, CommentUpdateExceptionHandler::class,
            FavoriteStatusExceptionHandler::class, NoteBlockCreateExceptionHandler::class,
            NoteBlockRemoveExceptionHandler::class, NoteBlockStatusExceptionHandler::class,
            NoteBlockListExceptionHandler::class, NoteDeletionExceptionHandler::class,
            NoteFavoriteExceptionHandler::class, NoteReadMutationExceptionHandler::class,
            NoteStreamExceptionHandler::class, NoteRecipientExceptionHandler::class,
            NoteNotificationsExceptionHandler::class, PrivateNoteReadExceptionHandler::class,
            TotalUserCountExceptionHandler::class, UserAnimalActivitiesExceptionHandler::class,
            MockOrderPaymentExceptionHandler::class,
        )

    }
}

/** Builds a class with a relaxed mock for each constructor parameter and remembers the mocks. */
object RelaxedBeans {
    private val byType = LinkedHashMap<KClass<*>, Any>()

    val mocks: List<Any>
        get() = byType.values.toList()

    inline fun <reified T : Any> mock(): T = mocks.filterIsInstance<T>().first()

    fun <T : Any> create(type: KClass<T>): T {
        val constructor = type.primaryConstructor ?: type.constructors.first()
        val arguments = constructor.parameters.map { parameter -> argument(parameter.type.classifier as KClass<*>) }
        return constructor.call(*arguments.toTypedArray())
    }

    private fun argument(type: KClass<*>): Any {
        return when (type) {
            String::class -> "synthetic"
            ObjectMapper::class -> ObjectMapper()
            Clock::class -> Clock.systemUTC()
            else -> byType.getOrPut(type) { mockkClass(type, relaxed = true) }
        }
    }
}
