package com.roomflow.domain.room;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoomStatusRequest {

  @NotNull(message = "enabled 不能为空")
  private Boolean enabled;
}
