package com.roomflow.domain.meeting;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * PUT full-update semantics: all fields are submitted; omitted description is treated as null. For
 * an already-started meeting only title/description may differ from the stored values (enforced in
 * the service with 40909, not bean validation).
 */
@Data
public class UpdateMeetingRequest {

  @NotBlank(message = "会议名称不能为空")
  @Size(max = 200, message = "会议名称长度不得超过 200")
  private String title;

  @Size(max = 5000, message = "会议说明长度不得超过 5000")
  private String description;

  @NotNull(message = "房间不能为空")
  @Min(value = 1, message = "roomId 必须为正整数")
  private Long roomId;

  /** Beijing time; must carry +08:00 offset (enforced in service, code 40001). */
  @NotNull(message = "开始时间不能为空")
  private OffsetDateTime startTime;

  @NotNull(message = "结束时间不能为空")
  private OffsetDateTime endTime;
}
