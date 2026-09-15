package com.roomflow.domain.room;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("room")
public class Room {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;
  private String location;
  private Integer capacity;

  /** Comma-separated Equipment enum names; see EquipmentCodec. */
  private String equipment;

  /** true=enabled; false=disabled (soft delete, historical meetings keep the reference). */
  private Boolean enabled;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
