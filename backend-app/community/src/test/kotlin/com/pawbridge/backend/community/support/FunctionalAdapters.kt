package com.pawbridge.backend.community.support

import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.community.internal.post.application.PostSnapshot
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.post.application.PostWriter
import com.pawbridge.backend.community.internal.post.domain.PostToCreate
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.UserNicknameQuery
import java.util.Optional

/*
 * The Java tests passed lambdas for these single-method Kotlin interfaces.
 * Kotlin only converts lambdas for `fun interface`, so these adapters keep the same call style.
 */

fun postMediaUploader(delegate: (List<PostUpload>?) -> List<String>): PostMediaUploader =
    object : PostMediaUploader {
        override fun upload(uploads: List<PostUpload>?): List<String> = delegate(uploads)
    }

fun postWriter(delegate: (PostToCreate) -> PostSnapshot): PostWriter =
    object : PostWriter {
        override fun create(post: PostToCreate): PostSnapshot = delegate(post)
    }

fun userNicknameQuery(delegate: (Long) -> Optional<String>): UserNicknameQuery =
    object : UserNicknameQuery {
        override fun findNickname(userId: Long): Optional<String> = delegate(userId)
    }

fun postReader(delegate: (Long) -> Optional<PostSnapshot>): PostReader =
    object : PostReader {
        override fun findById(postId: Long): Optional<PostSnapshot> = delegate(postId)
    }

fun contactMemberQuery(delegate: (Long) -> ContactMember): ContactMemberQuery =
    object : ContactMemberQuery {
        override fun get(userId: Long): ContactMember = delegate(userId)
    }

fun contactMemberCleanupStore(delegate: (Long, Runnable) -> Unit): ContactMemberCleanupStore =
    object : ContactMemberCleanupStore {
        override fun remove(memberId: Long, requireDeletionPending: Runnable) =
            delegate(memberId, requireDeletionPending)
    }

fun postPageReader(delegate: (PostPageReader.Request) -> PostPageReader.Slice): PostPageReader =
    object : PostPageReader {
        override fun findPage(request: PostPageReader.Request): PostPageReader.Slice = delegate(request)
    }
