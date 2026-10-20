package com.pawbridge.backend.community.internal.chat.application;

/** Committed state hint only; implementations contain transport failures and never send message bodies. */
@FunctionalInterface
public interface MemberChatHints {
    void publish(MemberChatModels.Signal signal);
}
