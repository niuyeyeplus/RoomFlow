---
name: java-reviewer
description: Rigorous Java/Spring Boot code review for transactions, query safety, security, messaging, and business rules
argument-hint: "[files or scope]"
agent: java-reviewer
triggers:
  - user
  - model
allowed-tools:
  - read
  - grep
  - glob
  - exec
permissions:
  allow:
    - Exec(git diff*)
    - Exec(git log*)
    - Exec(git show*)
    - Exec(git status*)
    - Exec(mvnw*)
    - Exec(mvnw.cmd*)
    - Exec(./mvnw*)
    - Exec(mvn*)
---

Perform a rigorous Java/Spring Boot code review focusing on:

1. **Transaction correctness**
   - `@Transactional` `rollbackFor` matches thrown exception types
   - Propagation (`REQUIRED`, `REQUIRES_NEW`) matches business intent
   - No implicit transaction spanning across service boundaries
   - Side effects ordered correctly relative to commit

2. **MyBatis-Plus query safety**
   - No raw SQL concatenation; conditions parameterized
   - N+1 query risks; batch-fetch where needed
   - Logical-delete and auto-fill conventions respected

3. **Spring Security / JWT**
   - `@PreAuthorize` coverage on protected endpoints
   - JWT signature and claim validation on every protected request
   - Session token revocation on logout enforced
   - No privilege escalation paths

4. **RabbitMQ reliability**
   - Consumer idempotency (deduplication by message ID)
   - Retry and DLQ policies configured explicitly
   - No blocking of listener threads

5. **Business rule correctness**
   - Time-overlap detection uses `[start, end)` half-open semantics
   - Capacity enforcement counts the organizer as an attendee
   - Kick-and-ban logic prevents re-entry after ban
   - Room deletion guards prevent deleting rooms with active bookings
   - Session token revocation on logout invalidates the token

6. **Migration safety**
   - Flyway migration ordering and naming correct
   - Migrations backward-compatible or coordinated with code
   - No destructive DDL without a guard or rollback path

7. **Test quality**
   - Branch coverage for error paths
   - Testcontainers tests use isolated containers
   - No skipped or deleted tests to pass gates

## Review Scope
$ARGUMENTS

If no scope is provided, review the current working directory and recent
changes (`git diff`).

## Output Format
Provide a structured report with:
- **Summary**: One-paragraph overview of the changes
- **Issues**: file paths, line numbers, severity (CRITICAL / HIGH / MEDIUM / LOW),
  and specific risk + recommendation
- **Suggestions**: Improvements that are not bugs but would make the code better
- **PASS / NEEDS_FIX** verdict
- Priority-ordered action items

## Important
- This is a read-only review. Do not modify any code.
- Delegate fixes to `java-spring-developer`.
- Do not merge, deploy, or add new specialist profiles.
