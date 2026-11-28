# SQLite for Intel Macs (`natives/osx_x64/libsqliteJni.dylib`)

`androidx.sqlite:sqlite-bundled` 2.7.1 ships its native SQLite for Linux (x64, arm64), Windows x64 and Apple Silicon
Macs only. On an Intel Mac the desktop app stopped at start with
`NoClassDefFoundError: Could not initialize class androidx.sqlite.driver.bundled.BundledSQLiteDriver$NativeLibraryObject`
(2026-11-28, per explicit request).

The driver looks for `natives/<os>_<arch>/libsqliteJni.dylib` on the classpath, so the Intel build lives in
`src/main/resources/natives/osx_x64/` and is packed into the desktop jar. Other platforms are untouched: they still use
the library inside `sqlite-bundled-jvm`.

## What it was built from

| Part | Source | SHA-256 |
|---|---|---|
| SQLite 3.50.1 (the version in `sqlite-bundled` 2.7.1) | https://www.sqlite.org/2025/sqlite-amalgamation-3500100.zip | `41716b44ac8777188c4c3f1f370f01c9cb9e3b6428eb5c981d086c35de2d9d3f` |
| JNI bindings | androidx `sqlite/sqlite-bundled/src/jvmAndAndroidMain/jni/sqlite_bindings.cpp` at `58fa83534ba68a3d3f2abf50001bdd9a593ef865` | `f9f4747111a6635dffd5991126247ca0f2f6c851de1eaf4add3866a0518ac2e0` |
| Result | `libsqliteJni.dylib`, x86_64, macOS 11+ | `4371ea927ce4df9ab2e38e8479f9befb12a4d2392cfc12144ff2aeb6c6015ee8` |

The bindings register the same 22 native methods, with the same signatures, as the 2.7.1 driver's `external fun`s.
The compile flags are androidx's `SQLITE_COMPILE_FLAGS` (from `sqlite/sqlite-bundled/build.gradle`).

Checked on an Intel Mac with the 2.7.1 driver: open a file database, create, insert and read text, real, integer and
blob values, `sqlite_version()` = 3.50.1, JSON functions available.

## Rebuilding (on any Mac with Xcode and JDK 21)

```bash
FLAGS="-DHAVE_USLEEP=1 -DSQLITE_DEFAULT_AUTOVACUUM=1 -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1 \
 -DSQLITE_ENABLE_COLUMN_METADATA -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS4 \
 -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_JSON1 -DSQLITE_ENABLE_MATH_FUNCTIONS -DSQLITE_ENABLE_NORMALIZE -DSQLITE_ENABLE_RTREE \
 -DSQLITE_ENABLE_STAT4 -DSQLITE_HAVE_ISNAN -DSQLITE_OMIT_BUILTIN_TEST -DSQLITE_OMIT_DEPRECATED \
 -DSQLITE_OMIT_PROGRESS_CALLBACK -DSQLITE_OMIT_SHARED_CACHE -DSQLITE_SECURE_DELETE -DSQLITE_TEMP_STORE=3 \
 -DSQLITE_THREADSAFE=2"
JH=$(/usr/libexec/java_home -v 21)
clang -arch x86_64 -mmacosx-version-min=11.0 -O2 -fPIC -c $FLAGS sqlite-amalgamation-3500100/sqlite3.c -o sqlite3.o
clang++ -arch x86_64 -mmacosx-version-min=11.0 -O2 -fPIC -std=c++17 -Dat_quick_exit=atexit -Dquick_exit=exit \
  -I"$JH/include" -I"$JH/include/darwin" -Isqlite-amalgamation-3500100 -c sqlite_bindings.cpp -o sqlite_bindings.o
clang++ -arch x86_64 -mmacosx-version-min=11.0 -dynamiclib -install_name libsqliteJni.dylib \
  -o libsqliteJni.dylib sqlite_bindings.o sqlite3.o
```

When `androidx-sqlite-bundled` is upgraded, check whether the new version ships `natives/osx_x64/` itself (then delete
this file and the dylib); otherwise rebuild with that version's SQLite and bindings.

Licences: SQLite is in the public domain; the bindings are Apache 2.0 (The Android Open Source Project).
