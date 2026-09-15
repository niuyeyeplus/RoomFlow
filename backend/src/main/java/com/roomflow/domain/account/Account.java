package com.roomflow.domain.account;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roomflow.common.enums.Role;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("account")
public class Account {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String username;
  private String passwordHash;
  private Role role;

  /** 1=normal, 0=disabled. */
  private Integer status;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
