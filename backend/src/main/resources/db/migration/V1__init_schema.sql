-- RoomFlow V1: five core business tables (account / room / meeting / participant / notification)
-- All times are stored as Beijing time (Asia/Shanghai) DATETIME.
-- Meeting logical deletion reuses status='DELETED' (no separate deleted flag, no @TableLogic).

CREATE TABLE account (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=normal, 0=disabled',
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_username UNIQUE (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE room (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    location   VARCHAR(200) NULL,
    capacity   INT          NOT NULL,
    equipment  VARCHAR(500) NULL COMMENT 'comma-separated Equipment enum names',
    enabled    TINYINT      NOT NULL DEFAULT 1 COMMENT '1=enabled, 0=disabled (soft delete)',
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_room_capacity CHECK (capacity BETWEEN 1 AND 100),
    KEY idx_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE meeting (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    title        VARCHAR(200) NOT NULL,
    description  TEXT         NULL,
    room_id      BIGINT       NOT NULL,
    organizer_id BIGINT       NOT NULL,
    start_time   DATETIME     NOT NULL COMMENT 'Beijing time, [start,end) half-open interval',
    end_time     DATETIME     NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ENDED/CANCELLED/DELETED',
    ended_early  TINYINT      NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_room_status_time (room_id, status, start_time, end_time),
    KEY idx_organizer (organizer_id),
    CONSTRAINT fk_meeting_room FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT fk_meeting_organizer FOREIGN KEY (organizer_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE participant (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    meeting_id   BIGINT   NOT NULL,
    account_id   BIGINT   NOT NULL,
    is_organizer TINYINT  NOT NULL DEFAULT 0,
    banned       TINYINT  NOT NULL DEFAULT 0 COMMENT '1=kicked, permanently banned from this meeting',
    joined_at    DATETIME NOT NULL,
    left_at      DATETIME NULL COMMENT 'NULL = still participating',
    leave_reason VARCHAR(20) NULL COMMENT 'NULL=active, USER_LEFT=voluntary (may rejoin), KICKED=banned',
    PRIMARY KEY (id),
    CONSTRAINT uk_meeting_account UNIQUE (meeting_id, account_id),
    KEY idx_meeting (meeting_id),
    CONSTRAINT fk_participant_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id),
    CONSTRAINT fk_participant_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE notification (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    account_id BIGINT       NOT NULL COMMENT 'recipient',
    type       VARCHAR(30)  NOT NULL COMMENT 'PARTICIPANT_JOINED/PARTICIPANT_LEFT/PARTICIPANT_KICKED/MEETING_ENDED',
    title      VARCHAR(200) NOT NULL,
    content    TEXT         NOT NULL,
    meeting_id BIGINT       NULL,
    is_read    TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_account_read (account_id, is_read, created_at DESC),
    CONSTRAINT fk_notification_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_notification_meeting FOREIGN KEY (meeting_id) REFERENCES meeting (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;