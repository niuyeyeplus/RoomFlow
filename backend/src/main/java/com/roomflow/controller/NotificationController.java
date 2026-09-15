package com.roomflow.controller;

import com.roomflow.common.result.PageResult;
import com.roomflow.common.result.Result;
import com.roomflow.domain.notification.NotificationVO;
import com.roomflow.security.SecurityUtils;
import com.roomflow.service.NotificationService;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@Validated
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping
  public Result<PageResult<NotificationVO>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Boolean isRead) {
    return Result.ok(notificationService.list(SecurityUtils.currentAccount(), isRead, page, size));
  }

  /** Literal "read-all" wins over "/{id}" (which only matches int64). */
  @PatchMapping("/read-all")
  public Result<Integer> markAllRead() {
    return Result.ok(notificationService.markAllRead(SecurityUtils.currentAccount()));
  }

  @PatchMapping("/{id}/read")
  public Result<NotificationVO> markRead(@PathVariable @Min(1) Long id) {
    return Result.ok(notificationService.markRead(id, SecurityUtils.currentAccount()));
  }
}
