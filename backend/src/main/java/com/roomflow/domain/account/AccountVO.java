package com.roomflow.domain.account;

import com.roomflow.common.enums.Role;
import com.roomflow.common.util.BeijingTime;
import java.time.OffsetDateTime;
import lombok.Data;

/** Account view object; never carries passwordHash. */
@Data
public class AccountVO {

  private Long id;
  private String username;
  private Role role;
  private Integer status;
  private OffsetDateTime createdAt;

  public static AccountVO from(Account account) {
    AccountVO vo = new AccountVO();
    vo.setId(account.getId());
    vo.setUsername(account.getUsername());
    vo.setRole(account.getRole());
    vo.setStatus(account.getStatus());
    vo.setCreatedAt(BeijingTime.toOffset(account.getCreatedAt()));
    return vo;
  }
}
