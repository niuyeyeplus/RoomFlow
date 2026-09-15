package com.roomflow.domain.room;

import com.roomflow.common.enums.Equipment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** PUT full update: omitted location means null, omitted equipment means empty list. */
@Data
public class UpdateRoomRequest {

  @NotBlank(message = "房间名称不能为空")
  @Size(max = 100, message = "房间名称长度不得超过 100")
  private String name;

  @Size(max = 200, message = "位置描述长度不得超过 200")
  private String location;

  @NotNull(message = "容量不能为空")
  @Min(value = 1, message = "容量须为 1-100")
  @Max(value = 100, message = "容量须为 1-100")
  private Integer capacity;

  private List<Equipment> equipment;
}
