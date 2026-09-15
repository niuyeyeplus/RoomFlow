package com.roomflow.common.enums;

/**
 * USER_LEFT allows rejoining by reusing the row; KICKED sets banned=1 and permanently blocks
 * rejoin.
 */
public enum LeaveReason {
  USER_LEFT,
  KICKED
}
