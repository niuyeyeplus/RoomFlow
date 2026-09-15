package com.roomflow.domain.room;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

@Data
public class RoomAvailabilityVO {

  private Long roomId;
  private LocalDate startDate;
  private LocalDate endDate;
  private List<TimeSlot> occupiedSlots;
}
