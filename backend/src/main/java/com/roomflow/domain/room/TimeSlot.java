package com.roomflow.domain.room;

import java.time.OffsetDateTime;
import lombok.Data;

/** Occupied [startTime, endTime) half-open slot. */
@Data
public class TimeSlot {

  private Long meetingId;
  private OffsetDateTime startTime;
  private OffsetDateTime endTime;
}
