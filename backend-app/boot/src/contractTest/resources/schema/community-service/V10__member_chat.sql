CREATE TABLE pawbridge_community.member_chat_rooms (
    room_id uuid PRIMARY KEY,
    first_member_id bigint,
    second_member_id bigint,
    latest_sequence bigint NOT NULL DEFAULT 0,
    CHECK (first_member_id IS NULL OR second_member_id IS NULL OR first_member_id < second_member_id),
    UNIQUE (first_member_id, second_member_id)
);

CREATE TABLE pawbridge_community.member_chat_room_members (
    room_id uuid NOT NULL REFERENCES pawbridge_community.member_chat_rooms ON DELETE CASCADE,
    member_id bigint NOT NULL,
    read_through bigint NOT NULL DEFAULT 0 CHECK (read_through >= 0),
    hidden_through bigint NOT NULL DEFAULT -1,
    PRIMARY KEY (room_id, member_id)
);
CREATE INDEX member_chat_members_owner ON pawbridge_community.member_chat_room_members(member_id, room_id);

CREATE TABLE pawbridge_community.member_chat_messages (
    room_id uuid NOT NULL REFERENCES pawbridge_community.member_chat_rooms ON DELETE CASCADE,
    sequence bigint NOT NULL,
    sender_id bigint,
    body text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    context_type varchar(6),
    context_id bigint,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (room_id, sequence),
    CHECK ((context_type IS NULL AND context_id IS NULL) OR
           (context_type IN ('POST','REPORT') AND context_id > 0)),
    CHECK (expires_at > created_at)
);
CREATE INDEX member_chat_messages_expiry ON pawbridge_community.member_chat_messages(expires_at);

-- Kept independently of messages so expiration does not turn a late retry into a fresh send.
CREATE TABLE pawbridge_community.member_chat_requests (
    sender_id bigint NOT NULL,
    request_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    room_id uuid NOT NULL,
    sequence bigint NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (sender_id, request_id)
);
CREATE INDEX member_chat_requests_rate ON pawbridge_community.member_chat_requests(sender_id, created_at);
