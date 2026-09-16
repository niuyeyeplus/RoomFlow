package com.roomflow.controller;

import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.result.PageResult;
import com.roomflow.common.result.Result;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.MeetingDetailVO;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.domain.meeting.UpdateMeetingRequest;
import com.roomflow.security.SecurityUtils;
import com.roomflow.service.MeetingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Meeting lifecycle API: list/detail/create plus update/cancel/delete/end-early. */
@RestController
@RequestMapping("/api/meetings")
@Validated
public class MeetingController {

  private final MeetingService meetingService;

  public MeetingController(MeetingService meetingService) {
    this.meetingService = meetingService;
  }

  @GetMapping
  public Result<PageResult<MeetingVO>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Min(1) Long roomId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) MeetingStatus status,
      @RequestParam(defaultValue = "false") boolean onlyMine) {
    return Result.ok(
        meetingService.list(
            page, size, roomId, date, status, onlyMine, SecurityUtils.currentAccount()));
  }

  @GetMapping("/{id}")
  public Result<MeetingDetailVO> get(@PathVariable @Min(1) Long id) {
    return Result.ok(meetingService.getDetail(id));
  }

  @PostMapping
  public ResponseEntity<Result<MeetingVO>> create(
      @Valid @RequestBody CreateMeetingRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Result.ok(meetingService.create(request, SecurityUtils.currentAccount())));
  }

  /** Organizer-or-admin check happens in the service (needs the meeting's organizerId). */
  @PutMapping("/{id}")
  public Result<MeetingVO> update(
      @PathVariable @Min(1) Long id, @Valid @RequestBody UpdateMeetingRequest request) {
    return Result.ok(meetingService.update(id, request, SecurityUtils.currentAccount()));
  }

  @PatchMapping("/{id}/cancel")
  public Result<MeetingVO> cancel(@PathVariable @Min(1) Long id) {
    return Result.ok(meetingService.cancel(id, SecurityUtils.currentAccount()));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable @Min(1) Long id) {
    meetingService.delete(id, SecurityUtils.currentAccount());
    return Result.ok();
  }

  @PatchMapping("/{id}/end-early")
  public Result<MeetingVO> endEarly(@PathVariable @Min(1) Long id) {
    return Result.ok(meetingService.endEarly(id, SecurityUtils.currentAccount()));
  }
}
