package com.roomflow.domain.participant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.roomflow.common.enums.LeaveReason;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * One row per (meeting, account) — uk_meeting_account is a plain unique constraint. Join/leave/kick
 * are expressed by updating leftAt/banned/leaveReason on that row, never by inserting a second row.
 */
@Data
@TableName("participant")
public class Participant {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long meetingId;
  private Long accountId;
  private Boolean isOrganizer;

  /** true = kicked and permanently banned from this meeting; never reset. */
  private Boolean banned;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime joinedAt;

  /** null = actively participating. */
  private LocalDateTime leftAt;

  private LeaveReason leaveReason;
}
