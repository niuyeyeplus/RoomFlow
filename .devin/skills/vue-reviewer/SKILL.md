---
name: vue-reviewer
description: Rigorous Vue 3 / TypeScript code review for reactivity, type safety, route guards, UI states, and Element Plus usage
argument-hint: "[files or scope]"
agent: vue-reviewer
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
    - Exec(npm run*)
    - Exec(npx vue-tsc*)
    - Exec(npx vitest*)
---

Perform a rigorous Vue 3 / TypeScript code review focusing on:

1. **API request error handling**
   - Network errors, 401/403, 4xx, and 5xx all handled
   - Retry logic correct and bounded
   - Login state recovery works (token refresh or redirect to login)
   - Registration, kick, meeting cancel/delete, and room delete operations
     show user feedback

2. **UI state completeness**
   - Loading state shown during async operations
   - Empty state shown when no data
   - Error state shown on failure with recovery options
   - No-permission state shown when the user lacks authorization
   - No silent failures or stuck spinners

3. **TypeScript type safety**
   - `vue-tsc --noEmit` passes with zero errors
   - No `any` types without justification; no implicit `any` leaks
   - Component props and emits are typed
   - API response types match the OpenAPI contract

4. **Pinia store reactivity and design**
   - State, getters, and actions clearly separated
   - Reactivity preserved (no manual reassignment of reactive refs)
   - Raw API responses normalized before entering store state
   - No stale-state bugs after navigation or logout

5. **Vue Router guard authentication**
   - Protected routes have navigation guards checking auth state
   - Unauthenticated users redirected to login with return redirect
   - Lazy-loaded route components where appropriate

6. **Element Plus usage and form validation**
   - Form validation rules with explicit triggers; validation before submit
   - Confirm dialogs for destructive actions (kick, meeting cancel/delete,
     room delete)
   - Success/error messages for all user-facing operations

7. **Component correctness**
   - `v-model` bindings correct and two-way
   - Component prop types safe; no runtime type mismatches
   - Dead code and unused imports removed

## Review Scope
$ARGUMENTS

If no scope is provided, review the current working directory and recent
changes (`git diff`).

## Output Format
Provide a structured report with:
- **Summary**: One-paragraph overview of the changes
- **Issues**: file paths, line numbers, severity (CRITICAL / HIGH / MEDIUM / LOW),
  and user-behavior reproduction steps
- **Suggestions**: Improvements that are not bugs but would make the code better
- **PASS / NEEDS_FIX** verdict
- Priority-ordered action items

## Important
- This is a read-only review. Do not modify any code.
- Delegate fixes to `vue-developer`.
- Do not merge, deploy, run backend commands, or add new specialist profiles.
