-- ============================================================
-- Chat Long-Term Memory Module - Database Schema
-- ============================================================

-- Table 0: SPRING_AI_CHAT_MEMORY - Spring AI JDBC chat memory (pre-create with utf8mb4)
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    `conversation_id` VARCHAR(36) NOT NULL,
    `content`         TEXT NOT NULL,
    `type`            ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') NOT NULL,
    `timestamp`       TIMESTAMP NOT NULL,
    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table 1: chat_session - tracks each conversation session
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`              VARCHAR(36)   NOT NULL PRIMARY KEY COMMENT 'UUID, same as conversation_id in SPRING_AI_CHAT_MEMORY',
    `user_id`         VARCHAR(64)   NOT NULL COMMENT 'External user identifier',
    `title`           VARCHAR(255)  DEFAULT NULL COMMENT 'Auto-generated session title',
    `summary`         TEXT          DEFAULT NULL COMMENT 'LLM-generated session summary',
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1=active, 0=archived',
    INDEX `idx_chat_session_user_id` (`user_id`),
    INDEX `idx_chat_session_user_active` (`user_id`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table 2: user_memory_facts - extracted long-term facts
CREATE TABLE IF NOT EXISTS `user_memory_facts` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id`         VARCHAR(64)   NOT NULL,
    `conversation_id` VARCHAR(36)   DEFAULT NULL COMMENT 'Source conversation where fact was extracted',
    `fact_text`       TEXT          NOT NULL COMMENT 'The extracted fact in natural language',
    `category`        VARCHAR(64)   DEFAULT 'general' COMMENT 'Category: preference, personal_info, work, habit, etc.',
    `importance`      TINYINT       NOT NULL DEFAULT 5 COMMENT 'Importance score 1-10',
    `document_id`     VARCHAR(128)  DEFAULT NULL COMMENT 'Milvus Document ID for the embedded vector',
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1,
    INDEX `idx_memory_facts_user_id` (`user_id`),
    INDEX `idx_memory_facts_user_category` (`user_id`, `category`),
    INDEX `idx_memory_facts_user_active` (`user_id`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table 3: user_preferences - structured user preferences
CREATE TABLE IF NOT EXISTS `user_preferences` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id`         VARCHAR(64)   NOT NULL,
    `preference_key`  VARCHAR(128)  NOT NULL COMMENT 'e.g., language, tone, response_style, topic_interest',
    `preference_value` TEXT         NOT NULL COMMENT 'The preference value',
    `confidence`      DECIMAL(3,2)  NOT NULL DEFAULT 0.50 COMMENT 'Confidence score 0.00-1.00',
    `source`          VARCHAR(64)   DEFAULT 'extracted' COMMENT 'Source: explicit (user stated) or extracted (LLM inferred)',
    `document_id`     VARCHAR(128)  DEFAULT NULL COMMENT 'Milvus Document ID',
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX `idx_user_pref_key` (`user_id`, `preference_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Table 4: memory_extraction_log - audit trail for extractions
CREATE TABLE IF NOT EXISTS `memory_extraction_log` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` VARCHAR(36)   NOT NULL,
    `user_id`         VARCHAR(64)   NOT NULL,
    `extraction_type` VARCHAR(32)   NOT NULL COMMENT 'fact, preference, summary',
    `input_message_count` INT       DEFAULT NULL,
    `extracted_count` INT           DEFAULT NULL,
    `status`          VARCHAR(16)   NOT NULL DEFAULT 'success' COMMENT 'success, failed, skipped',
    `error_message`   TEXT          DEFAULT NULL,
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_extraction_log_user` (`user_id`),
    INDEX `idx_extraction_log_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
