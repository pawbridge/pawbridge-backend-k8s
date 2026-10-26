-- Lifecycle lookups and FK cleanup must not scan every mailbox/request per removed note.
CREATE INDEX idx_private_note_mailbox_note ON private_note_mailboxes (note_id);
CREATE INDEX idx_private_note_sender ON private_notes (sender_id) WHERE sender_id IS NOT NULL;
CREATE INDEX idx_private_note_recipient ON private_notes (recipient_id) WHERE recipient_id IS NOT NULL;
CREATE INDEX idx_private_note_request_note ON private_note_requests (note_id) WHERE note_id IS NOT NULL;
CREATE INDEX idx_private_note_request_cleanup ON private_note_requests (created_at) WHERE note_id IS NULL;
