-- RoomFlow V2: seed data.
-- Three preset meeting rooms and the initial ADMIN account.
--
-- The admin password hash is injected through the Flyway placeholder
-- ${admin-password-hash}, bound from spring.flyway.placeholders.admin-password-hash:
--   dev          -> committed DEV-ONLY bootstrap hash (documented dev credential)
--   staging/prod -> ${ADMIN_PASSWORD_HASH} env var with NO default; a missing
--                   value fails property resolution so the app refuses to boot.
-- Known limitation: there is no change-password API (outside the API contract),
-- so rotating the staging/prod admin credential means redeploying with a newly
-- generated BCrypt hash.
--
-- Timestamps are stored as Beijing time derived from UTC so the seeded values
-- do not depend on the MySQL session time zone.
INSERT INTO room (name, location, capacity, equipment, enabled, created_at, updated_at)
VALUES
    ('301会议室', '3楼东侧', 8, 'PROJECTOR,WHITEBOARD',
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'),
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')),
    ('302会议室', '3楼西侧', 12, 'PROJECTOR,VIDEO_CONFERENCE',
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'),
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')),
    ('多功能厅', '5楼', 20, 'PROJECTOR,WHITEBOARD,VIDEO_CONFERENCE,PHONE',
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'),
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'));

INSERT INTO account (username, password_hash, role, status, created_at, updated_at)
VALUES ('admin', '${admin-password-hash}', 'ADMIN', 1,
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'),
        CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'));