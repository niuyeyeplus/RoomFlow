package com.roomflow.domain.meeting;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roomflow.common.enums.MeetingStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Logical deletion is custom: status=DELETED is the delete marker; queries must explicitly exclude
 * it. Do NOT add @TableLogic (status is a business enum, not a 0/1 flag).
 */
@Data
@TableName("meeting")
public class Meeting {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String title;
  private String description;
  private Long roomId;
  private Long organizerId;

  /** Beijing time [start,end) half-open interval. */
  private LocalDateTime startTime;

  private LocalDateTime endTime;
  private MeetingStatus status;
  private Boolean endedEarly;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
