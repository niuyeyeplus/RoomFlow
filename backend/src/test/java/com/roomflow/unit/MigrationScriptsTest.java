package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Regression guard: Flyway's PlaceholderReplacingReader resolves ${name} tokens ANYWHERE in a
 * migration script — including comments and string literals — and fails the whole migration when
 * the name is not a configured placeholder. Every ${...} token in db/migration must therefore be a
 * known placeholder; today the only legal one is admin-password-hash (bound via
 * spring.flyway.placeholders.admin-password-hash).
 *
 * <p>Runs under surefire with no Docker: it only reads classpath resources.
 */
class MigrationScriptsTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)\\}");
  private static final String ALLOWED = "admin-password-hash";

  private static Resource[] scripts() throws IOException {
    return new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
  }

  @Test
  void noUnknownPlaceholderAnywhereInMigrationScripts() throws IOException {
    Resource[] resources = scripts();
    assertTrue(resources.length >= 2, "expected at least V1+V2 migration scripts on the classpath");

    for (Resource resource : resources) {
      String sql = resource.getContentAsString(StandardCharsets.UTF_8);
      Set<String> names = new LinkedHashSet<>();
      Matcher m = PLACEHOLDER.matcher(sql);
      while (m.find()) {
        names.add(m.group(1));
      }
      for (String name : names) {
        assertEquals(
            ALLOWED,
            name,
            resource.getFilename()
                + " uses ${"
                + name
                + "} which is not a configured Flyway placeholder"
                + " (comments and literals are resolved too)");
      }
    }
  }

  @Test
  void v2ActuallyInjectsAdminPasswordHash() throws IOException {
    for (Resource resource : scripts()) {
      if ("V2__seed_rooms.sql".equals(resource.getFilename())) {
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(
            sql.contains("'${" + ALLOWED + "}'"),
            "V2 must inject the admin hash via the " + ALLOWED + " placeholder");
        return;
      }
    }
    fail("V2__seed_rooms.sql not found on the classpath");
  }
}
