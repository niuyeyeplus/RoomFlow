package com.roomflow.controller;

import com.roomflow.common.result.Result;
import com.roomflow.domain.participant.ParticipantVO;
import com.roomflow.security.SecurityUtils;
import com.roomflow.service.ParticipantService;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings/{meetingId}/participants")
@Validated
public class ParticipantController {

  private final ParticipantService participantService;

  public ParticipantController(ParticipantService participantService) {
    this.participantService = participantService;
  }

  /** Join: success always returns 200, including rejoining after a voluntary leave. */
  @PostMapping
  public Result<ParticipantVO> join(@PathVariable @Min(1) Long meetingId) {
    return Result.ok(participantService.join(meetingId, SecurityUtils.currentAccount()));
  }

  @GetMapping
  public Result<List<ParticipantVO>> list(@PathVariable @Min(1) Long meetingId) {
    return Result.ok(participantService.list(meetingId));
  }

  @DeleteMapping("/me")
  public Result<Void> leave(@PathVariable @Min(1) Long meetingId) {
    participantService.leave(meetingId, SecurityUtils.currentAccount());
    return Result.ok();
  }

  /** Kick: organizer-or-admin check happens in the service (needs the meeting's organizer). */
  @DeleteMapping("/{accountId}")
  public Result<Void> kick(
      @PathVariable @Min(1) Long meetingId, @PathVariable @Min(1) Long accountId) {
    participantService.kick(meetingId, accountId, SecurityUtils.currentAccount());
    return Result.ok();
  }
}
