CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    sequence_number INTEGER NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chat_messages_conversation_sequence UNIQUE (conversation_id, sequence_number),
    CONSTRAINT fk_chat_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES chat_conversations(conversation_id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_messages_conversation_sequence
    ON chat_messages (conversation_id, sequence_number);
