import { defineConfig, devices } from '@playwright/test'

// E2E against a real backend + real middleware (no mocks).
//
// ---------------------------------------------------------------------------
// Target resolution - ONE environment per project, mirrored in three places
// (this file, tests/e2e/helpers.ts, and the guard at the top of
// staging-smoke.spec.ts). Keep the three in step.
//
//   dev / CI - the `chromium` project, i.e. every committed spec
//     UI  base: E2E_BASE_URL     -> http://localhost:5173   (Vite dev server)
//     API base: E2E_API_BASE_URL -> http://localhost:8080   (tests/e2e/helpers.ts)
//
//   staging - the `staging` project, i.e. every staging-*.spec.ts file
//     UI  base: PLAYWRIGHT_BASE_URL -> STAGING_BASE_URL
//     API base: STAGING_API_BASE_URL -> the staging UI URL  (same origin)
//
// With no env vars set this file resolves to exactly the previous behaviour:
// baseURL http://localhost:5173, and `chromium` as the only project.
//
// Rule 1 - a staging URL is the staging project's OWN `use.baseURL`, never the
//   global one. A top-level `use.baseURL` is inherited by EVERY project, so a
//   staging URL placed there silently repoints the committed dev specs at
//   staging: a bare `npx playwright test` with STAGING_BASE_URL exported (or a CI
//   job that exports it) would run auth/meeting-flow/permission against a shared,
//   publicly reachable deployed database. The global baseURL therefore stays on
//   the dev chain for good, and `staging` is the only thing here that can ever
//   target staging.
//
// Rule 2 - the UI and API chains resolve from the SAME staging variable, so a
//   staging run cannot split across two backends. `stagingURL` below is the
//   single staging switch; helpers.ts derives its API base from the very same
//   expression rather than from a separately-ordered chain (which is how a run
//   could previously drive the browser against staging while every helper call
//   went to the locally exported dev API).
//
// Staging opt-in: the `staging` project exists ONLY when a staging URL is
// actually configured. The staging host is provisioned separately and does not
// exist yet, so an unconditional second project would make plain
// `npx playwright test` - and CI - try to reach a host that is not there.
//
// Run staging with `npm run test:e2e:staging`: its `pretest:e2e:staging` hook
// checks the environment and prints an actionable message before Playwright
// starts, instead of Playwright failing with `Project(s) "staging" not found`.
//
// Two consequences worth knowing about:
//   * PLAYWRIGHT_BASE_URL is now a staging-class variable: it selects the target
//     of the `staging` project and no longer moves the dev suite. To aim the dev
//     suite at another UI, use E2E_BASE_URL.
//   * helpers.ts resolves ONE API base from the environment, not per project, so
//     exporting a staging URL and then running the dev suite (a bare
//     `npx playwright test`) would leave `chromium` on the dev UI while helper
//     setup talked to the staging API. Do not do that: run the staging project on
//     its own and keep the staging variables out of a dev run's environment.
// ---------------------------------------------------------------------------

const DEV_UI_BASE_URL = 'http://localhost:5173'

// Dev/CI UI chain. Deliberately free of staging variables - see rule 1.
const devBaseURL = process.env.E2E_BASE_URL ?? DEV_UI_BASE_URL

// Staging chain: the run-scoped PLAYWRIGHT_BASE_URL wins over the
// deployment-scoped STAGING_BASE_URL. Both names are staging-class, so they only
// ever reach the `staging` project. Unset -> no `staging` project at all.
const stagingURL = process.env.PLAYWRIGHT_BASE_URL ?? process.env.STAGING_BASE_URL

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results/artifacts',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  // Specs share one real dev database; run serially to keep assertions deterministic.
  workers: 1,
  retries: process.env.CI ? 2 : 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'test-results/html', open: 'never' }],
    ['junit', { outputFile: 'test-results/junit.xml' }]
  ],
  use: {
    baseURL: devBaseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 15_000
  },
  projects: [
    {
      name: 'chromium',
      // The staging specs belong to the `staging` project only, so a
      // default (no staging env) run never even collects them.
      testIgnore: /staging-smoke\.spec\.ts/,
      // Inherits the dev `use.baseURL` above; it must never see a staging URL.
      use: { ...devices['Desktop Chrome'] }
    },
    // Present only when a staging URL is configured - see the comment block above.
    ...(stagingURL
      ? [
          {
            name: 'staging',
            testMatch: /staging-.*\.spec\.ts/,
            // The staging specs hit one real shared database; keep the run serial.
            fullyParallel: false,
            workers: 1,
            // Rule 1: the staging URL is scoped to THIS project, not global.
            // trace/video are OFF here on purpose: the staging specs type real,
            // env-supplied credentials, and Playwright traces capture DOM state
            // (including input values) while videos/artifacts are uploaded to CI.
            // A retained artifact could carry a credential to anyone who can
            // download run artifacts, so staging failures are debugged from
            // screenshots + the junit/html report only.
            use: {
              ...devices['Desktop Chrome'],
              baseURL: stagingURL,
              trace: 'off',
              video: 'off',
              screenshot: 'only-on-failure'
            }
          }
        ]
      : [])
  ]
})
