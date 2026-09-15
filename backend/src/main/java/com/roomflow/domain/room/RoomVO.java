package com.roomflow.domain.room;

import com.roomflow.common.enums.Equipment;
import com.roomflow.common.util.BeijingTime;
import com.roomflow.common.util.EquipmentCodec;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

@Data
public class RoomVO {

  private Long id;
  private String name;
  private String location;
  private Integer capacity;
  private List<Equipment> equipment;
  private Boolean enabled;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  public static RoomVO from(Room room) {
    RoomVO vo = new RoomVO();
    vo.setId(room.getId());
    vo.setName(room.getName());
    vo.setLocation(room.getLocation());
    vo.setCapacity(room.getCapacity());
    vo.setEquipment(EquipmentCodec.decode(room.getEquipment()));
    vo.setEnabled(room.getEnabled());
    vo.setCreatedAt(BeijingTime.toOffset(room.getCreatedAt()));
    vo.setUpdatedAt(BeijingTime.toOffset(room.getUpdatedAt()));
    return vo;
  }
}
