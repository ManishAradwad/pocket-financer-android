# Repository Development Instructions

Read `copilot-instructions.md` for project architecture, build, test, and
emulator context. Verify documentation against the current code before relying
on it.

## Holistic Change Discipline

For every feature and bug fix, trace the behavior end to end before
implementation. Inspect every relevant production entry point and state owner,
including UI and ViewModels, persisted settings, foreground and background
processing, services and workers, inference and native code, repositories, and
restart or recovery paths.

Explicitly resolve all material states and transitions: defaults and upgrades,
idle, queued and in-flight behavior, concurrent or repeated actions,
cancellation, failure and retry, app or process restart, and downstream data or
cache compatibility. Consider privacy, security, latency, battery, memory,
accessibility, and truthful user feedback wherever relevant.

Runtime settings must have a defined consistency boundary. Choose explicitly
between safe immediate application, application to the next operation using a
per-operation snapshot, or temporarily disabling or deferring the control while
busy. Never allow one operation to run partly with the old value and partly with
the new value. Surface any material product choice rather than silently
guessing.

Test the primary behavior and relevant transitions or regressions at the lowest
practical layer. Run affected module tests and the appropriate build check.
Document intentionally unsupported cases and meaningful tradeoffs without
adding speculative complexity.
