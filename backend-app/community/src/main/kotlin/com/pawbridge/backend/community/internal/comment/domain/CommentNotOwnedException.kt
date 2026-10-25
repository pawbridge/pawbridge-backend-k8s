package com.pawbridge.backend.community.internal.comment.domain

class CommentNotOwnedException : RuntimeException("댓글에 접근할 권한이 없습니다.")
