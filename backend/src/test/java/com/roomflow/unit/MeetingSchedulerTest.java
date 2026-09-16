package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roomflow.service.MeetingScheduler;
import com.roomflow.service.MeetingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeetingSchedulerTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

  @Mock private MeetingService meetingService;

  private MeetingScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new MeetingScheduler(meetingService, Clock.fixed(NOW, ZONE));
  }

  @Test
  void sweepEndsEveryOverdueCandidate() {
    when(meetingService.findOverdueActiveMeetingIds(any())).thenReturn(List.of(11L, 12L));
    when(meetingService.endMeetingIfActive(any())).thenReturn(true);

    scheduler.sweepExpiredMeetings();

    verify(meetingService).endMeetingIfActive(11L);
    verify(meetingService).endMeetingIfActive(12L);
  }

  @Test
  void sweepIsolatesPerMeetingFailures() {
    when(meetingService.findOverdueActiveMeetingIds(any())).thenReturn(List.of(11L, 12L));
    when(meetingService.endMeetingIfActive(11L)).thenThrow(new RuntimeException("db glitch"));
    when(meetingService.endMeetingIfActive(12L)).thenReturn(true);

    // One failing meeting must not abort the rest of the sweep.
    scheduler.sweepExpiredMeetings();

    verify(meetingService).endMeetingIfActive(11L);
    verify(meetingService).endMeetingIfActive(12L);
  }

  @Test
  void sweepSkipsMeetingsAlreadyTransitioned() {
    // Conditional UPDATE lost the race (concurrent end-early/cancel or another node's sweep).
    when(meetingService.findOverdueActiveMeetingIds(any())).thenReturn(List.of(11L));
    when(meetingService.endMeetingIfActive(11L)).thenReturn(false);

    scheduler.sweepExpiredMeetings();

    verify(meetingService).endMeetingIfActive(11L);
  }

  @Test
  void sweepWithNoOverdueMeetingsDoesNothing() {
    when(meetingService.findOverdueActiveMeetingIds(any())).thenReturn(List.of());

    scheduler.sweepExpiredMeetings();

    verify(meetingService, never()).endMeetingIfActive(any());
  }

  /**
   * Unit-level ShedLock mutual exclusion: two nodes share one LockProvider that only ever grants
   * the lock once (modelling a lock already held — lockAtLeastFor retention — on the other node).
   * Only the node that acquires the lock may run the sweep.
   */
  @Test
  void sweepRunsOnAtMostOneNodeWhileLockHeld() {
    AtomicBoolean lockTaken = new AtomicBoolean();
    LockProvider provider =
        config ->
            lockTaken.compareAndSet(false, true)
                ? Optional.of((SimpleLock) () -> {})
                : Optional.empty();
    DefaultLockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
    LockConfiguration lockConfig =
        new LockConfiguration(
            Instant.now(), "meeting-end-sweep", Duration.ofMinutes(5), Duration.ofSeconds(1));
    when(meetingService.findOverdueActiveMeetingIds(any())).thenReturn(List.of(7L));
    when(meetingService.endMeetingIfActive(7L)).thenReturn(true);

    runLocked(executor, lockConfig);
    runLocked(executor, lockConfig); // second node: lock still held -> skipped

    verify(meetingService, times(1)).findOverdueActiveMeetingIds(any());
    verify(meetingService, times(1)).endMeetingIfActive(7L);
  }

  /** Concurrent contention on the shared lock: exactly one of the racing sweeps may execute. */
  @Test
  void concurrentSweepsAcquireLockExactlyOnce() throws InterruptedException {
    AtomicBoolean lockTaken = new AtomicBoolean();
    LockProvider provider =
        config ->
            lockTaken.compareAndSet(false, true)
                ? Optional.of(
                    (SimpleLock) () -> {}) // never released: the loser must stay locked out
                : Optional.empty();
    DefaultLockingTaskExecutor executor = new DefaultLockingTaskExecutor(provider);
    LockConfiguration lockConfig =
        new LockConfiguration(
            Instant.now(), "meeting-end-sweep", Duration.ofMinutes(5), Duration.ofSeconds(1));
    when(meetingService.findOverdueActiveMeetingIds(any())).thenReturn(List.of());
    AtomicInteger executions = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Runnable competing =
          () -> {
            try {
              executor.executeWithLock(
                  () -> {
                    executions.incrementAndGet();
                    scheduler.sweepExpiredMeetings();
                    return null;
                  },
                  lockConfig);
            } catch (Throwable t) {
              throw new RuntimeException(t);
            }
          };
      pool.submit(competing);
      pool.submit(competing);
      pool.shutdown();
      assertEquals(true, pool.awaitTermination(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, executions.get());
    verify(meetingService, times(1)).findOverdueActiveMeetingIds(any());
  }

  private void runLocked(DefaultLockingTaskExecutor executor, LockConfiguration lockConfig) {
    try {
      executor.executeWithLock(
          () -> {
            scheduler.sweepExpiredMeetings();
            return null;
          },
          lockConfig);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
