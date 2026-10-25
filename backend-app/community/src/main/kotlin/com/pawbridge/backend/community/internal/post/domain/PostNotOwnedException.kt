package com.pawbridge.backend.community.internal.post.domain

class PostNotOwnedException : RuntimeException("게시글에 접근할 권한이 없습니다.")
