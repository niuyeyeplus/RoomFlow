package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.roomflow.common.exception.BizException;
import com.roomflow.common.util.TimeRules;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** All time-rule branches for meeting creation (contract step 2, code 40905 / 40001). */
class TimeRulesTest {

  private static final ZoneOffset CST = ZoneOffset.of("+08:00");
  private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z"); // 16:00 +08:00

  private static OffsetDateTime at(String iso) {
    return OffsetDateTime.parse(iso);
  }

  private static int codeOf(BizException e) {
    return e.getErrorCode().getCode();
  }

  @Test
  void rejectsPastStart() {
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                TimeRules.validateMeetingWindow(
                    at("2026-09-15T15:45:00+08:00"), at("2026-09-15T16:45:00+08:00"), NOW));
    assertEquals(40905, codeOf(e));
  }

  @Test
  void rejectsStartBeyond72h() {
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                TimeRules.validateMeetingWindow(
                    at("2026-09-18T16:15:00+08:00"), at("2026-09-18T17:15:00+08:00"), NOW));
    assertEquals(40905, codeOf(e));
  }

  @Test
  void acceptsStartExactlyAt72hBoundary() {
    assertDoesNotThrow(
        () ->
            TimeRules.validateMeetingWindow(
                at("2026-09-18T16:00:00+08:00"), at("2026-09-18T17:00:00+08:00"), NOW));
  }

  @Test
  void rejectsMisalignedStart() {
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                TimeRules.validateMeetingWindow(
                    at("2026-09-15T16:07:00+08:00"), at("2026-09-15T17:00:00+08:00"), NOW));
    assertEquals(40905, codeOf(e));
  }

  @Test
  void rejectsMisalignedEnd() {
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                TimeRules.validateMeetingWindow(
                    at("2026-09-15T17:00:00+08:00"), at("2026-09-15T17:52:00+08:00"), NOW));
    assertEquals(40905, codeOf(e));
  }

  @Test
  void rejectsNonZeroSeconds() {
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                TimeRules.validateMeetingWindow(
                    at("2026-09-15T17:00:30+08:00"), at("2026-09-15T18:00:00+08:00"), NOW));
    assertEquals(40905, codeOf(e));
  }

  @Test
  void rejectsDurationUnder15min() {
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                TimeRules.validateMeetingWindow(
                    at("2026-09-15T17:00:00+08:00"), at("2026-09-15T17:00:00+08:00"), NOW));
    assertEquals(40905, codeOf(e));
  }

  @Test
  void rejectsDurationOver24h() {
    BizException e =
        assertThrows(
            BizException.class,
            () ->
                TimeRules.validateMeetingWindow(
                    at("2026-09-15T17:00:00+08:00"), at("2026-09-16T17:15:00+08:00"), NOW));
    assertEquals(40905, codeOf(e));
  }

  @Test
  void acceptsMin15minAndCrossDay() {
    assertDoesNotThrow(
        () ->
            TimeRules.validateMeetingWindow(
                at("2026-09-15T17:00:00+08:00"), at("2026-09-15T17:15:00+08:00"), NOW));
    assertDoesNotThrow(
        () ->
            TimeRules.validateMeetingWindow(
                at("2026-09-15T23:30:00+08:00"), at("2026-09-16T00:30:00+08:00"), NOW));
  }

  @Test
  void acceptsMax24hAndEndBeyondWindow() {
    // Duration exactly 24h, ending far outside the 72h start window is allowed.
    assertDoesNotThrow(
        () ->
            TimeRules.validateMeetingWindow(
                at("2026-09-18T15:45:00+08:00"), at("2026-09-19T15:45:00+08:00"), NOW));
  }

  @Test
  void rejectsNonBeijingOffsetAsFieldError() {
    BizException e =
        assertThrows(
            BizException.class,
            () -> TimeRules.requireBeijingOffset(at("2026-09-15T17:00:00Z"), "startTime"));
    assertEquals(40001, codeOf(e));
  }

  @Test
  void acceptsBeijingOffset() {
    assertDoesNotThrow(
        () -> TimeRules.requireBeijingOffset(at("2026-09-15T17:00:00+08:00"), "startTime"));
  }
}
