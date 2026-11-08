CREATE TABLE chat_conversations (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_chat_conversations_updated_at
    ON chat_conversations (updated_at DESC);
