-- Private contents are never written to the public post/search/outbox tables.
CREATE TABLE private_note_members (
    member_id BIGINT PRIMARY KEY CHECK (member_id > 0)
);
CREATE TABLE private_notes (
    note_id UUID PRIMARY KEY,
    sender_id BIGINT REFERENCES private_note_members(member_id) ON DELETE SET NULL,
    recipient_id BIGINT REFERENCES private_note_members(member_id) ON DELETE SET NULL,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 5000),
    reply_to UUID REFERENCES private_notes(note_id) ON DELETE SET NULL,
    context_type VARCHAR(10) CHECK (context_type IN ('POST','REPORT')),
    context_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((context_type IS NULL) = (context_id IS NULL)),
    CHECK (sender_id IS NULL OR recipient_id IS NULL OR sender_id <> recipient_id)
);
CREATE INDEX idx_private_notes_expiry ON private_notes (expires_at, note_id);
CREATE TABLE private_note_mailboxes (
    member_id BIGINT NOT NULL REFERENCES private_note_members(member_id) ON DELETE CASCADE,
    note_id UUID NOT NULL REFERENCES private_notes(note_id) ON DELETE CASCADE,
    direction VARCHAR(5) NOT NULL CHECK (direction IN ('INBOX','SENT')),
    read_at TIMESTAMPTZ,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (member_id, note_id)
);
CREATE INDEX idx_private_note_mailbox_page ON private_note_mailboxes (member_id, direction, created_at DESC, note_id DESC);
CREATE INDEX idx_private_note_mailbox_favorite ON private_note_mailboxes (member_id, created_at DESC, note_id DESC) WHERE favorite;
CREATE INDEX idx_private_note_mailbox_unread ON private_note_mailboxes (member_id, created_at DESC, note_id DESC) WHERE direction = 'INBOX' AND read_at IS NULL;
CREATE TABLE private_note_requests (
    sender_id BIGINT NOT NULL REFERENCES private_note_members(member_id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64),
    note_id UUID REFERENCES private_notes(note_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (sender_id, request_id)
);
CREATE INDEX idx_private_note_request_rate ON private_note_requests (sender_id, created_at DESC);
CREATE TABLE private_note_blocks (
    member_id BIGINT NOT NULL REFERENCES private_note_members(member_id) ON DELETE CASCADE,
    blocked_id BIGINT NOT NULL REFERENCES private_note_members(member_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (member_id, blocked_id),
    CHECK (member_id <> blocked_id)
);
