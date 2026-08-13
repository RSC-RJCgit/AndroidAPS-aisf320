# Codex workspace notes

- Do not run Gradle or Android Studio build/compile commands from Codex in this workspace. This environment's Gradle path is known not to work. Use static source, XML, and diff checks, and leave compilation to the user's own build workflow unless the user explicitly asks to try Gradle again.