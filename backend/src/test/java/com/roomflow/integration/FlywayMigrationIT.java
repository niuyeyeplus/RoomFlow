package com.roomflow.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies V1/V2 Flyway migrations on a real MySQL 8.4 container. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FlywayMigrationIT extends AbstractContainersIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void allFiveCoreTablesExist() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'roomflow'",
            String.class);
    assertTrue(tables.contains("account"));
    assertTrue(tables.contains("room"));
    assertTrue(tables.contains("meeting"));
    assertTrue(tables.contains("participant"));
    assertTrue(tables.contains("notification"));
  }

  @Test
  void seedsThreeRoomsAndAdmin() {
    // ITs share one container database; other ITs may create extra rooms, so the seed
    // assertions are scoped to the three V2 seed names instead of the table row count.
    Integer rooms =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM room WHERE name IN ('301会议室','302会议室','多功能厅')", Integer.class);
    assertEquals(3, rooms);
    List<String> names =
        jdbcTemplate.queryForList(
            "SELECT name FROM room WHERE name IN ('301会议室','302会议室','多功能厅') ORDER BY id",
            String.class);
    assertEquals(List.of("301会议室", "302会议室", "多功能厅"), names);
    List<Integer> capacities =
        jdbcTemplate.queryForList(
            "SELECT capacity FROM room WHERE name IN ('301会议室','302会议室','多功能厅') ORDER BY id",
            Integer.class);
    assertEquals(List.of(8, 12, 20), capacities);

    String role =
        jdbcTemplate.queryForObject(
            "SELECT role FROM account WHERE username = 'admin'", String.class);
    assertEquals("ADMIN", role);
    String hash =
        jdbcTemplate.queryForObject(
            "SELECT password_hash FROM account WHERE username = 'admin'", String.class);
    assertNotNull(hash);
    assertTrue(hash.startsWith("$2"), "admin password must be BCrypt-hashed");
  }

  /**
   * DEV-ONLY check: asserts the hash V2 seeds via the {@code admin-password-hash} placeholder
   * (supplied by application-dev.yml / AbstractContainersIT) verifies against the documented dev
   * bootstrap credential admin/Admin@123456. The plaintext here is the documented dev-only
   * password; staging/prod inject their own hash via ADMIN_PASSWORD_HASH.
   */
  @Test
  void adminSeedPasswordMatchesDocumentedValue() {
    String hash =
        jdbcTemplate.queryForObject(
            "SELECT password_hash FROM account WHERE username = 'admin'", String.class);
    assertNotNull(hash);
    assertTrue(
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
            .matches("Admin@123456", hash),
        "seeded admin hash must verify against the documented dev-only initial password");
  }

  @Test
  void participantUniqueKeyExists() {
    List<String> constraints =
        jdbcTemplate.queryForList(
            "SELECT constraint_name FROM information_schema.table_constraints"
                + " WHERE table_schema='roomflow' AND table_name='participant'"
                + " AND constraint_type='UNIQUE'",
            String.class);
    assertTrue(constraints.contains("uk_meeting_account"));
  }
}
