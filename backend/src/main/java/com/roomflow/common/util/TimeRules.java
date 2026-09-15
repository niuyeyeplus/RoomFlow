package com.roomflow.common.util;

import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.common.result.ValidationErrors;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Meeting time rules (contract): both times must carry the +08:00 offset; [start,end) is a
 * half-open interval; both times aligned to 15 minutes; start within the rolling [now, now+72h]
 * window; duration 15min-24h inclusive; crossing midnight allowed; end may exceed the 72h window.
 */
public final class TimeRules {

  public static final Duration MIN_DURATION = Duration.ofMinutes(15);
  public static final Duration MAX_DURATION = Duration.ofHours(24);
  public static final Duration MAX_START_AHEAD = Duration.ofHours(72);

  private TimeRules() {}

  /**
   * Field-level contract check: only the +08:00 offset is accepted (Z or other offsets rejected).
   */
  public static void requireBeijingOffset(OffsetDateTime time, String field) {
    if (time == null) {
      return;
    }
    if (!BeijingTime.OFFSET.equals(time.getOffset())) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of(field, "时间必须使用北京时间 +08:00 偏移"));
    }
  }

  /**
   * Validates the full time-rule set. Any violation throws 40905.
   *
   * @param now evaluation instant (injected Clock for testability)
   */
  public static void validateMeetingWindow(OffsetDateTime start, OffsetDateTime end, Instant now) {
    if (!isQuarterAligned(start) || !isQuarterAligned(end)) {
      throw new BizException(ErrorCode.TIME_RULE, "起止时间必须按 15 分钟对齐（秒为 0 且分钟为 15 的倍数）");
    }
    if (start.toInstant().isBefore(now)) {
      throw new BizException(ErrorCode.TIME_RULE, "开始时间不得早于当前时间");
    }
    if (start.toInstant().isAfter(now.plus(MAX_START_AHEAD))) {
      throw new BizException(ErrorCode.TIME_RULE, "开始时间不得晚于当前时间后 72 小时");
    }
    Duration duration = Duration.between(start.toInstant(), end.toInstant());
    if (duration.compareTo(MIN_DURATION) < 0) {
      throw new BizException(ErrorCode.TIME_RULE, "会议时长不得少于 15 分钟");
    }
    if (duration.compareTo(MAX_DURATION) > 0) {
      throw new BizException(ErrorCode.TIME_RULE, "会议时长不得超过 24 小时");
    }
  }

  private static boolean isQuarterAligned(OffsetDateTime time) {
    return time.getSecond() == 0 && time.getNano() == 0 && time.getMinute() % 15 == 0;
  }
}
