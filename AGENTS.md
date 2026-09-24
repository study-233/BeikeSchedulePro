# Repository Instructions

## Language

- Write plans in Chinese. Use Chinese for progress updates and final responses unless the user requests another language.
- Keep this `AGENTS.md` in English.
- Follow the surrounding language and style when editing source comments, UI text, and other documentation.

## Working Scope

- Work in source-editing mode: inspect repository files and make the code, test, resource, and documentation changes needed for the user's request.
- Read-only repository inspection, including file searches and Git status or diff inspection, is allowed.
- Leave execution to the user: do not run builds, tests, linters, Gradle tasks, application code, dependency installation, or code generators under this scope.
- Do not launch applications, install APKs, operate devices or emulators, or run ADB commands, including Logcat collection. Analyze logs supplied by the user when relevant.
- Do not change the machine's SDK, JDK, environment variables, IDE settings, or global tool configuration.
- Do not commit, push, publish, or deploy changes unless the user explicitly changes the scope to request that action.
- The availability of Android Studio, Gradle, an SDK, or a connected phone does not expand this scope. A request to implement a feature or fix a bug authorizes source changes only.
- Complete authorized source changes without repeatedly asking to perform execution steps. Explain which checks the user can run when they are relevant.

## Project Context

BeikeSchedule is an Android application for University of Science and Technology Beijing students. It supports schedules, academic records, exams, personal reminders, and free classroom information. The project uses Kotlin, Jetpack Compose with Material 3, Room, DataStore, and AlarmManager in a single `app` module.

Read `README.md` for current features and `docs/JWXT_API.md` before changing academic system integration. Other documents under `docs/` provide design and review history; compare older documents with current code and configuration before treating them as requirements.

Production Kotlin sources are under `app/src/main/java/com/caeamer/beikeschedule/`:

| Location | Responsibility |
| --- | --- |
| `ui/` | Compose screens, ViewModels, shared UI, and theme |
| `model/` | Domain models and schedule or date logic |
| `data/local/` | Room database, entities, DAOs, and migrations |
| `data/pref/` | Preferences and application session state |
| `data/repo/` | Repositories and calculation logic |
| `data/remote/` | Free classroom API integration and parsing |
| `import/` | Academic system login, WebView integration, and import flow |
| `reminder/` | Reminder scheduling and broadcast receivers |

Import scripts live in `app/src/main/assets/import/`. JVM tests and fixtures are under `app/src/test/`; device tests are under `app/src/androidTest/`. Versioned Room schemas are under `app/schemas/`.

## Editing Conventions

- Inspect the relevant implementation and existing tests before editing. Preserve the project's established architecture and naming conventions.
- Keep changes focused on the requested behavior. Preserve unrelated local changes and avoid broad formatting or dependency upgrades.
- Keep parsing, calculations, and data access out of Compose rendering code where the existing layers provide a suitable home.
- Preserve existing user data. When changing Room entities, update the database version and migration code as needed. Identify required schema generation and migration verification for the user; do not run generators under the current scope.
- Preserve the local storage and login privacy model. Do not read or store academic account passwords, expose session credentials, or add uploads of personal data without an explicit product requirement.
- Use existing API documentation and fixtures for integration changes. Avoid live requests to school services during source editing.
- Add or adjust focused test source when behavior changes warrant it. Do not add tests solely to mirror implementation details or verify documentation edits.
- Keep local configuration, signing credentials, logs containing personal data, and generated build artifacts out of changes intended for version control.

## Portable Environment Guidance

- Use repository-relative paths in shared instructions and configuration. Do not assume a developer's operating system, username, drive letter, SDK location, or Android Studio installation path.
- Treat `gradle/wrapper/gradle-wrapper.properties`, `gradle/gradle-daemon-jvm.properties`, `app/build.gradle.kts`, and `gradle/libs.versions.toml` as the sources for toolchain, SDK, and dependency requirements. The current Gradle daemon configuration requests Java 21.
- When describing commands for the user, use the repository wrapper: `.\gradlew.bat` in Windows PowerShell or `./gradlew` on macOS/Linux. Do not require a globally installed Gradle.
- SDK and JDK locations vary by machine. Refer to the developer's existing local configuration rather than inserting machine-specific paths into the repository.
- Android Studio and an agent terminal may use different JDKs, caches, or permissions. A failure in one environment does not establish that the developer's installation is missing or broken.

## Verification and Handoff

Review the edited source and diff for consistency, unintended changes, and obvious errors. Do not claim compilation, tests, or device behavior were verified when no execution occurred.

The CI workflow in `.github/workflows/ci.yml` is the source for automated checks. The following tasks are references for the user, not instructions for the agent to execute under the current scope:

| Gradle task | Purpose |
| --- | --- |
| `testDebugUnitTest` | JVM unit tests |
| `lintDebug` | Android Lint |
| `assembleDebug` | Debug APK build |
| `minifyReleaseWithR8` | Release shrinking and optimization checks without APK signing |

A complete signed release build also needs the developer's private signing configuration. Device testing and Logcat collection depend on the developer's available device or emulator; do not assume one is connected.

In the final response, briefly describe the changes, the source review performed, and any relevant manual verification still needed. State that executable checks were not run because the task is limited to source editing.
