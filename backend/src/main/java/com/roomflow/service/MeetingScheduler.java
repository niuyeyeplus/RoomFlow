package com.roomflow.service;

import java.time.Clock;
import java.time.LocalDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sweep that ends overdue meetings. ShedLock (Redis) guarantees a single executor across
 * instances; the per-meeting transition itself is a conditional UPDATE guarded on status='ACTIVE',
 * so duplicate sweeps or races with manual end-early/cancel/delete are idempotent and can never
 * double-end or double-notify.
 *
 * <p>lock-at-least (default PT50S) is deliberately near the 60s interval: a second instance that
 * misses the lock skips the whole cycle instead of re-running a redundant (idempotent but wasted)
 * sweep right after the winner releases. lock-at-most stays well above any plausible sweep time.
 */
@Component
public class MeetingScheduler {

  private static final Logger log = LoggerFactory.getLogger(MeetingScheduler.class);

  private final MeetingService meetingService;
  private final Clock clock;

  public MeetingScheduler(MeetingService meetingService, Clock clock) {
    this.meetingService = meetingService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${roomflow.scheduler.interval:60s}")
  @SchedulerLock(
      name = "meeting-end-sweep",
      lockAtMostFor = "${roomflow.scheduler.lock-at-most:PT5M}",
      lockAtLeastFor = "${roomflow.scheduler.lock-at-least:PT50S}")
  public void sweepExpiredMeetings() {
    LocalDateTime now = LocalDateTime.now(clock);
    for (Long meetingId : meetingService.findOverdueActiveMeetingIds(now)) {
      try {
        if (meetingService.endMeetingIfActive(meetingId)) {
          log.info("Auto-ended overdue meeting {}", meetingId);
        }
      } catch (RuntimeException e) {
        // Isolate per-meeting failures: one bad row must not abort the rest of the sweep.
        log.error("Failed to auto-end meeting {}", meetingId, e);
      }
    }
  }
}
