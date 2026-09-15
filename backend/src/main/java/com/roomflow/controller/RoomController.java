package com.roomflow.controller;

import com.roomflow.common.result.Result;
import com.roomflow.domain.room.CreateRoomRequest;
import com.roomflow.domain.room.RoomAvailabilityVO;
import com.roomflow.domain.room.RoomStatusRequest;
import com.roomflow.domain.room.RoomVO;
import com.roomflow.domain.room.UpdateRoomRequest;
import com.roomflow.service.RoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/api/rooms")
@Validated
public class RoomController {

  private final RoomService roomService;

  public RoomController(RoomService roomService) {
    this.roomService = roomService;
  }

  @GetMapping
  public Result<List<RoomVO>> list(@RequestParam(required = false) Boolean enabled) {
    return Result.ok(roomService.list(enabled));
  }

  @GetMapping("/{id}")
  public Result<RoomVO> get(@PathVariable @Min(1) Long id) {
    return Result.ok(roomService.get(id));
  }

  @GetMapping("/{id}/availability")
  public Result<RoomAvailabilityVO> availability(
      @PathVariable @Min(1) Long id,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate endDate) {
    return Result.ok(roomService.availability(id, startDate, endDate));
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Result<RoomVO>> create(@Valid @RequestBody CreateRoomRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(roomService.create(request)));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<RoomVO> update(
      @PathVariable @Min(1) Long id, @Valid @RequestBody UpdateRoomRequest request) {
    return Result.ok(roomService.update(id, request));
  }

  @PatchMapping("/{id}/status")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<RoomVO> updateStatus(
      @PathVariable @Min(1) Long id, @Valid @RequestBody RoomStatusRequest request) {
    return Result.ok(roomService.updateStatus(id, request));
  }

  /** Soft delete (enabled=false); rejects when unfinished ACTIVE meetings exist (40907). */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<Void> delete(@PathVariable @Min(1) Long id) {
    roomService.delete(id);
    return Result.ok();
  }
}
