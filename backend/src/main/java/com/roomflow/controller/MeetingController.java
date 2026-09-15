package com.roomflow.controller;

import com.roomflow.common.enums.MeetingStatus;
import com.roomflow.common.result.PageResult;
import com.roomflow.common.result.Result;
import com.roomflow.domain.meeting.CreateMeetingRequest;
import com.roomflow.domain.meeting.MeetingDetailVO;
import com.roomflow.domain.meeting.MeetingVO;
import com.roomflow.security.SecurityUtils;
import com.roomflow.service.MeetingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice-1 scope: list/detail/create only. PUT/cancel/DELETE/end-early are contract-defined but
 * intentionally implemented in the meeting-lifecycle slice (PR-4).
 */
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
}
