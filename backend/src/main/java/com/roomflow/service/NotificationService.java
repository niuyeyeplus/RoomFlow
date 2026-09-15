package com.roomflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.common.result.PageResult;
import com.roomflow.common.result.ValidationErrors;
import com.roomflow.domain.notification.Notification;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.domain.notification.NotificationVO;
import com.roomflow.mapper.NotificationMapper;
import com.roomflow.security.LoginAccount;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

  private static final int MAX_PAGE_SIZE = 100;

  private final NotificationMapper notificationMapper;

  public NotificationService(NotificationMapper notificationMapper) {
    this.notificationMapper = notificationMapper;
  }

  /** Own notifications only; unread first (isRead ASC), then createdAt DESC, id DESC. */
  public PageResult<NotificationVO> list(LoginAccount current, Boolean isRead, int page, int size) {
    if (page < 1) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of("page", "page 必须 >= 1"));
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new BizException(
          ErrorCode.PARAM_INVALID,
          ErrorCode.PARAM_INVALID.getDefaultMessage(),
          ValidationErrors.of("size", "size 必须在 1-100 之间"));
    }
    LambdaQueryWrapper<Notification> query =
        new LambdaQueryWrapper<Notification>()
            .eq(Notification::getAccountId, current.id())
            .orderByAsc(Notification::getIsRead)
            .orderByDesc(Notification::getCreatedAt)
            .orderByDesc(Notification::getId);
    if (isRead != null) {
      query.eq(Notification::getIsRead, isRead);
    }
    Page<Notification> mpPage = notificationMapper.selectPage(new Page<>(page, size), query);
    List<NotificationVO> records = mpPage.getRecords().stream().map(NotificationVO::from).toList();
    return new PageResult<>(records, mpPage.getTotal(), page, size);
  }

  /** Idempotent read marker. Others' notifications and missing ids both return 40401. */
  @Transactional(rollbackFor = Exception.class)
  public NotificationVO markRead(Long id, LoginAccount current) {
    Notification notification = id == null ? null : notificationMapper.selectById(id);
    if (notification == null || !notification.getAccountId().equals(current.id())) {
      throw new BizException(ErrorCode.NOT_FOUND);
    }
    if (!Boolean.TRUE.equals(notification.getIsRead())) {
      notification.setIsRead(true);
      notificationMapper.updateById(notification);
    }
    return NotificationVO.from(notification);
  }

  /** Marks all of the caller's unread notifications read; returns the number of rows updated. */
  @Transactional(rollbackFor = Exception.class)
  public int markAllRead(LoginAccount current) {
    return notificationMapper.update(
        null,
        new LambdaUpdateWrapper<Notification>()
            .eq(Notification::getAccountId, current.id())
            .eq(Notification::getIsRead, false)
            .set(Notification::getIsRead, true));
  }

  /** Persists a notification consumed from MQ. Called only by the idempotent consumer. */
  @Transactional(rollbackFor = Exception.class)
  public void saveFromMessage(NotificationMessage message) {
    Notification notification = new Notification();
    notification.setAccountId(message.accountId());
    notification.setType(message.type());
    notification.setTitle(message.title());
    notification.setContent(message.content());
    notification.setMeetingId(message.meetingId());
    notification.setIsRead(false);
    notificationMapper.insert(notification);
  }
}
