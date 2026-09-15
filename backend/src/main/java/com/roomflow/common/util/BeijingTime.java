package com.roomflow.common.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Single source of truth for the Asia/Shanghai (+08:00) business timezone. */
public final class BeijingTime {

  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  public static final ZoneOffset OFFSET = ZoneOffset.of("+08:00");

  private BeijingTime() {}

  /** Converts a +08:00 OffsetDateTime (already validated) to the stored Beijing LocalDateTime. */
  public static LocalDateTime toLocal(OffsetDateTime time) {
    return time == null ? null : time.atZoneSameInstant(ZONE).toLocalDateTime();
  }

  /** Converts a stored Beijing LocalDateTime to the wire format carrying the +08:00 offset. */
  public static OffsetDateTime toOffset(LocalDateTime time) {
    return time == null ? null : time.atOffset(OFFSET);
  }
}
