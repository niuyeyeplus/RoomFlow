package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.domain.notification.Notification;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.domain.notification.NotificationVO;
import com.roomflow.mapper.NotificationMapper;
import com.roomflow.security.LoginAccount;
import com.roomflow.service.NotificationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  private static final LoginAccount USER = new LoginAccount(1L, "alice", Role.USER);

  @Mock private NotificationMapper notificationMapper;
  private NotificationService service;

  @BeforeEach
  void setUp() {
    service = new NotificationService(notificationMapper);
  }

  private static int code(BizException e) {
    return e.getErrorCode().getCode();
  }

  @Test
  void listValidatesPageAndSize() {
    BizException e1 = assertThrows(BizException.class, () -> service.list(USER, null, 0, 10));
    assertEquals(40001, code(e1));
    BizException e2 = assertThrows(BizException.class, () -> service.list(USER, null, 1, 101));
    assertEquals(40001, code(e2));
  }

  @Test
  void listReturnsPagedOwnNotifications() {
    Notification n = new Notification();
    n.setId(1L);
    n.setAccountId(1L);
    n.setType(NotificationType.PARTICIPANT_JOINED);
    n.setIsRead(false);
    Page<Notification> page = new Page<>(1, 10);
    page.setRecords(List.of(n));
    page.setTotal(1);
    when(notificationMapper.selectPage(any(), any())).thenReturn(page);
    var result = service.list(USER, false, 1, 10);
    assertEquals(1, result.getTotal());
    assertEquals(NotificationType.PARTICIPANT_JOINED, result.getRecords().get(0).getType());
  }

  @Test
  void markReadRejectsMissingOrForeignNotification() {
    when(notificationMapper.selectById(5L)).thenReturn(null);
    assertThrows(BizException.class, () -> service.markRead(5L, USER));

    Notification foreign = new Notification();
    foreign.setId(5L);
    foreign.setAccountId(2L);
    when(notificationMapper.selectById(5L)).thenReturn(foreign);
    BizException e = assertThrows(BizException.class, () -> service.markRead(5L, USER));
    assertEquals(40401, code(e));
  }

  @Test
  void markReadIsIdempotent() {
    Notification n = new Notification();
    n.setId(5L);
    n.setAccountId(1L);
    n.setIsRead(true);
    when(notificationMapper.selectById(5L)).thenReturn(n);
    NotificationVO vo = service.markRead(5L, USER);
    assertEquals(true, vo.getIsRead());
    verify(notificationMapper, org.mockito.Mockito.never()).updateById(any(Notification.class));
  }

  @Test
  void markReadMarksUnread() {
    Notification n = new Notification();
    n.setId(5L);
    n.setAccountId(1L);
    n.setIsRead(false);
    when(notificationMapper.selectById(5L)).thenReturn(n);
    NotificationVO vo = service.markRead(5L, USER);
    assertEquals(true, vo.getIsRead());
    verify(notificationMapper).updateById(n);
  }

  @Test
  void markAllReadReturnsCount() {
    when(notificationMapper.update(any(), any())).thenReturn(3);
    assertEquals(3, service.markAllRead(USER));
  }

  @Test
  void saveFromMessagePersistsNotification() {
    NotificationMessage msg =
        new NotificationMessage("m1", 7L, NotificationType.PARTICIPANT_KICKED, "t", "c", 10L);
    service.saveFromMessage(msg);
    verify(notificationMapper)
        .insert(
            org.mockito.ArgumentMatchers.<Notification>argThat(
                (Notification n) ->
                    n.getAccountId().equals(7L)
                        && n.getType() == NotificationType.PARTICIPANT_KICKED
                        && Boolean.FALSE.equals(n.getIsRead())));
  }
}
