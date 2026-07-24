# Git Commit Convention

This repository uses a Conventional Commits-style format. Commit messages use English by default so that history, release notes, and tooling remain consistent across the Android App and Android Studio plugin.

## 1. Commit message format

```text
<type>(<scope>): <subject>
```

For a breaking change, add `!` after the scope:

```text
<type>(<scope>)!: <subject>
```

Example:

```text
feat(api)!: require expectedVersion when clearing text
```

The scope is recommended for every commit and required when the change clearly belongs to a module or subsystem. Use `repo` for repository-wide changes and `docs` for documentation-only changes.

## 2. Default language

- `type`, `scope`, and `subject` must be written in English.
- Commit bodies and footers should be written in English by default.
- Quoted API paths, UI labels, test data, and user-facing strings may retain their original language.
- Do not mix Chinese and English in the same subject unless the subject contains a required product or API name.

## 3. Commit types

| Type | Use for | Example |
| --- | --- | --- |
| `feat` | A new user-visible or developer-visible capability | `feat(app): persist the current text state` |
| `fix` | A bug fix or incorrect behavior correction | `fix(plugin): preserve newer phone text` |
| `docs` | Documentation-only changes | `docs(requirements): define version conflict behavior` |
| `refactor` | Code restructuring without changing behavior | `refactor(protocol): separate response models` |
| `test` | Adding or changing tests | `test(api): cover multiline responses` |
| `build` | Build scripts, packaging, or platform configuration | `build(plugin): configure the platform target` |
| `ci` | Continuous integration or automation configuration | `ci(repo): run module checks on pull requests` |
| `deps` | Adding, removing, or upgrading dependencies | `deps(app): update the TCP server library` |
| `perf` | A measurable performance improvement | `perf(plugin): avoid redundant port forwarding` |
| `chore` | Repository maintenance with no product behavior change | `chore(repo): add the initial project layout` |
| `revert` | Reverting an earlier commit | `revert(plugin): restore reconnect behavior` |

Use the most specific type available. For example, use `docs` instead of `chore` for a Markdown-only change, and use `deps` instead of `build` for a dependency version change.

## 4. Scopes

Use one scope per commit. Prefer the smallest subsystem that explains the change.

| Scope | Meaning |
| --- | --- |
| `app` | Android App module behavior, screens, or ViewModels |
| `server` | Android-side TCP Server lifecycle and handlers |
| `storage` | Android text-state persistence |
| `api` | TCP messages, request correlation, and protocol errors |
| `protocol` | Cross-module protocol contracts or shared semantics |
| `plugin` | Android Studio plugin lifecycle and project integration |
| `ui` | Tool Window UI and presentation state |
| `adb` | ADB discovery, device parsing, and port forwarding |
| `socket` | Plugin TCP client, timeouts, and lifecycle |
| `clipboard` | Clipboard write behavior and error handling |
| `settings` | Plugin settings and persisted configuration |
| `test` | Cross-module test infrastructure or test fixtures |
| `build` | Gradle, packaging, and platform build configuration |
| `deps` | Dependency declarations and version management |
| `docs` | Repository documentation |
| `repo` | Repository-wide structure or maintenance |

When a change spans multiple modules, choose the shared concept (`api`, `protocol`, or `repo`) instead of using a compound scope such as `app-plugin`.

## 5. Subject rules

The subject is the short summary after `: `.

- Write it in the imperative present tense: `add`, `fix`, `remove`, `clarify`.
- Start with a lowercase letter.
- Do not end it with a period.
- Keep it concise; 72 characters is the recommended maximum.
- Describe the result of the commit, not the editing process.
- Do not include implementation details that are not useful in history.

Good:

```text
feat(app): persist the current text state
fix(plugin): retry forwarding once after connection failure
docs(commits): define English commit message rules
```

Avoid:

```text
updated files
fix bug
一些修改
WIP
```

## 6. Commit body

Add a body when the subject does not fully explain the reason, trade-off, or behavior change.

Rules:

- Leave one blank line between the subject and body.
- Explain why the change is needed and what behavior it establishes.
- Wrap long lines at approximately 72–100 characters.
- Do not repeat the subject as the first body line.

Example:

```text
fix(plugin): avoid clearing newer phone text

Send the loaded version as expectedVersion when handling Copy & Clear.
The server can reject the command if the phone text changed after the snapshot was loaded.
```

## 7. Footers and breaking changes

Use footers for issue references, migration notes, or explicit breaking-change descriptions.

```text
feat(api): add version-aware clear requests

Require the client to send the version it loaded before clearing text.

BREAKING CHANGE: clients must send expectedVersion in the clear TCP command.
Refs: #42
```

Use either of these forms for a breaking change:

```text
feat(api)!: require expectedVersion on clear
```

or:

```text
feat(api): require expectedVersion on clear

BREAKING CHANGE: the clear endpoint rejects requests without expectedVersion.
```

The footer must describe the migration impact, not only state that the change is breaking.

## 8. Atomic commit rules

Each commit should have one clear purpose and be independently reviewable.

- Keep Android App, plugin, and documentation changes separate when they can be reviewed independently.
- Keep implementation and its tests together when the tests verify that implementation.
- Do not mix formatting-only changes with behavior changes.
- Do not include generated build output, local IDE files, secrets, or unrelated cleanup.
- If a change requires multiple commits, make the dependency order clear and keep each commit buildable when practical.
- Run the relevant tests and build checks before committing.

## 9. Recommended examples for this repository

```text
chore(repo): add the initial project layout
docs(protocol): define the local TCP contract
docs(commits): add the repository commit convention
feat(app): add the multiline text editor
feat(server): start the local TCP server on app launch
feat(protocol): add version-aware text clearing
feat(plugin): register the Input Bridge tool window
feat(adb): detect the configured adb executable
feat(socket): retry a failed connection after rebuilding forward
feat(clipboard): write the displayed text to the system clipboard
fix(plugin): ignore stale TCP callbacks
fix(protocol): preserve emoji and multiline text in messages
test(storage): restore the current text after process recreation
test(adb): parse unauthorized and offline devices
build(plugin): configure the IntelliJ Platform target
deps(app): add the selected TCP server dependency
refactor(protocol): share app and plugin TCP models
```

## 10. Before committing

```text
1. Confirm the change has one purpose.
2. Select the most specific type and scope.
3. Write an English imperative subject without a trailing period.
4. Add a body when the reason or impact is not obvious.
5. Add a BREAKING CHANGE footer when consumers must migrate.
6. Run the relevant tests and build checks.
7. Review the staged diff before creating the commit.
```
