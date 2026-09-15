package com.roomflow.common.util;

import com.roomflow.common.enums.Equipment;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Converts between the comma-separated room.equipment column and List<Equipment>. */
public final class EquipmentCodec {

  private EquipmentCodec() {}

  public static String encode(List<Equipment> equipment) {
    if (equipment == null || equipment.isEmpty()) {
      return null;
    }
    return equipment.stream().map(Equipment::name).distinct().collect(Collectors.joining(","));
  }

  public static List<Equipment> decode(String raw) {
    if (raw == null || raw.isBlank()) {
      return Collections.emptyList();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Equipment::valueOf)
        .distinct()
        .collect(Collectors.toList());
  }
}
