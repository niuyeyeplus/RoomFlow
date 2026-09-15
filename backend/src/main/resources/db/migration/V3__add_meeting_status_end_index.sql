-- RoomFlow V3: index for the meeting-end sweep.
-- MeetingScheduler polls WHERE status = 'ACTIVE' AND end_time <= now; the existing
-- idx_room_status_time index leads with room_id and cannot serve that predicate, so
-- every sweep would full-scan the meeting table.
ALTER TABLE meeting
    ADD KEY idx_status_end (status, end_time);