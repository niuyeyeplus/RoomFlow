package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

  /**
   * Structural check: for every INSERT ... (columns) VALUES (tuple), (...) — assert the column
   * count equals every tuple's value count. Aware of single-quoted strings (including '' escapes)
   * and nested parentheses such as CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'), so commas in
   * string literals and function calls are not miscounted.
   */
  @Test
  void insertColumnCountMatchesValuesArity() throws IOException {
    for (Resource resource : scripts()) {
      String sql = stripLineComments(resource.getContentAsString(StandardCharsets.UTF_8));
      for (String statement : splitStatements(sql)) {
        String normalized = statement.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.startsWith("INSERT INTO")) {
          continue;
        }
        List<Group> groups = topLevelParenGroups(statement);
        int valuesPos = keywordIndex(statement, "VALUES");
        assertTrue(valuesPos > 0, resource.getFilename() + ": INSERT without VALUES");
        List<Group> columnGroups = new ArrayList<>();
        List<Group> tuples = new ArrayList<>();
        for (Group g : groups) {
          (g.start < valuesPos ? columnGroups : tuples).add(g);
        }
        assertTrue(!tuples.isEmpty(), resource.getFilename() + ": INSERT has no VALUES tuples");
        int expected =
            columnGroups.isEmpty()
                ? arity(statement, tuples.get(0))
                : arity(statement, columnGroups.get(0));
        for (Group tuple : tuples) {
          assertEquals(
              expected,
              arity(statement, tuple),
              resource.getFilename()
                  + ": VALUES tuple arity does not match the INSERT column count: "
                  + statement.substring(tuple.start, tuple.end + 1));
        }
      }
    }
  }

  // ---------- minimal SQL structure scanner (strings + nested parens aware) ----------

  private record Group(int start, int end) {}

  /** Removes -- line comments while preserving string literals. */
  private static String stripLineComments(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    boolean inString = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (inString) {
        out.append(c);
        if (c == '\'') {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
            out.append(sql.charAt(++i));
          } else {
            inString = false;
          }
        }
        continue;
      }
      if (c == '\'') {
        inString = true;
        out.append(c);
      } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        while (i < sql.length() && sql.charAt(i) != '\n') {
          i++;
        }
        out.append('\n');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** Splits on ';' at paren depth 0 outside string literals. */
  private static List<String> splitStatements(String sql) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (inString) {
        current.append(c);
        if (c == '\'') {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
            current.append(sql.charAt(++i));
          } else {
            inString = false;
          }
        }
        continue;
      }
      if (c == '\'') {
        inString = true;
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ';' && depth == 0) {
        statements.add(current.toString());
        current.setLength(0);
        continue;
      }
      current.append(c);
    }
    if (!current.toString().isBlank()) {
      statements.add(current.toString());
    }
    return statements;
  }

  /** All depth-0 parenthesized groups of a statement (column list + VALUES tuples). */
  private static List<Group> topLevelParenGroups(String s) {
    List<Group> groups = new ArrayList<>();
    int depth = 0;
    boolean inString = false;
    int groupStart = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        if (c == '\'') {
          if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
            i++;
          } else {
            inString = false;
          }
        }
        continue;
      }
      if (c == '\'') {
        inString = true;
      } else if (c == '(') {
        if (depth++ == 0) {
          groupStart = i;
        }
      } else if (c == ')') {
        if (--depth == 0) {
          groups.add(new Group(groupStart, i));
        }
      }
    }
    return groups;
  }

  /** Index of a keyword outside string literals, or -1. */
  private static int keywordIndex(String s, String keyword) {
    String upper = s.toUpperCase(java.util.Locale.ROOT);
    boolean inString = false;
    for (int i = 0; i + keyword.length() <= s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        if (c == '\'') {
          if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
            i++;
          } else {
            inString = false;
          }
        }
        continue;
      }
      if (c == '\'') {
        inString = true;
      } else if (upper.startsWith(keyword, i)) {
        return i;
      }
    }
    return -1;
  }

  /** Comma count at paren depth 0 (outside strings) inside the group, +1 = value/column count. */
  private static int arity(String statement, Group group) {
    int commas = 0;
    int depth = 0;
    boolean inString = false;
    for (int i = group.start + 1; i < group.end; i++) {
      char c = statement.charAt(i);
      if (inString) {
        if (c == '\'') {
          if (i + 1 < group.end && statement.charAt(i + 1) == '\'') {
            i++;
          } else {
            inString = false;
          }
        }
        continue;
      }
      if (c == '\'') {
        inString = true;
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        commas++;
      }
    }
    return commas + 1;
  }
}
