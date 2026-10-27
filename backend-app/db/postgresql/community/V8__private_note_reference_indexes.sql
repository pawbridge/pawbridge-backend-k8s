-- FK SET NULL/CASCADE lookups must remain indexed during note expiry and member withdrawal.
CREATE INDEX idx_private_note_reply_to ON private_notes (reply_to) WHERE reply_to IS NOT NULL;
CREATE INDEX idx_private_note_blocked_member ON private_note_blocks (blocked_id);
