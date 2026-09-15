package com.roomflow.common.enums;

/**
 * ACTIVE occupies its time slot; DELETED is the logical-delete marker, invisible to all queries.
 */
public enum MeetingStatus {
  ACTIVE,
  ENDED,
  CANCELLED,
  DELETED
}
