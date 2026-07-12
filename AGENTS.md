# Agent Instructions for LineDJ

## Build & Test Commands

- **Build all**: `sbt publishLocal`
- **Test all**: `sbt test`
- **Test one subproject**: `sbt <projectName>/test` (e.g., `sbt playerEngine/test`)
- **Test one file**: `sbt "playerEngine/testOnly *AudioPlayerSpec"`
- **Build OSGi image**: `sbt osgiImage` (from an image project, e.g., `sbt playerOsgiImage/osgiImage`)
- **Build native image**: `sbt playerServer/GraalVMNativeImage/packageBin` (requires GraalVM 24+)
- **Scala version**: 3.3.7. Do not guess; check `build.sbt` for current version.

## Compiler Flags

`-Xfatal-warnings` is enabled. Any warning = build failure. This catches unused imports, deprecations, etc.

## Scala 3 Conventions

- **Indent-based syntax** (`-indent` flag). No braces for blocks; use indentation.
- **`-new-syntax`**: Use `if ... then ... else` (not `if (...) ... else ...`).
- **`given`/`using`** instead of `implicit`. No `implicit` keyword in new code.
- **`enum`** and **`extension`** methods are idiomatic here.

## Test Conventions

- **Framework**: ScalaTest 3.2.20 with FlatSpec style.
- **Class pattern**: `class FooSpec extends AnyFlatSpec with Matchers` (sync) or `class FooSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike with Matchers` (actors).
- **Naming**: Files end in `Spec` (not `Test`). Descriptions use `"A ClassName" should "behavior"`.
- **Mocking**: Mockito via `MockitoSugar`. Enable inline mock maker via `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` containing `mock-maker-inline`.
- **Shared test helpers** in `shared/src/test/scala/de/oliver_heger/linedj/`: `FileTestHelper`, `ActorTestKitSupport`, `AsyncTestHelper`, `StateTestHelper`, etc. Import from `de.oliver_heger.linedj`.
- **Test resources**: `src/test/resources/` for configs, test data, fixtures.
- **Tests are forked**: `ThisBuild / Test / fork := true`. Tests run in a separate JVM.
- **No integration test separation**: Unit and integration tests live together in `src/test/`.

## Project Structure

~50+ subprojects. Key layers:

- **shared**: Common code available to both client and server. Has test helpers shared via `test->test` dependency.
- **playerEngine**: Core player engine (library, not app).
- **radioPlayerEngine**: Internet radio engine (library).
- **platform**: OSGi client platform. Most UI apps depend on this.
- **audioPlatform**: Audio playback services on top of platform.
- **OSGi image projects** (`images/*`): Runnable application bundles. These assemble dependencies into deployable OSGi images.
- **mediaArchive/**: Media archive subsystem (scan, HTTP, cloud, union, server, startup).
- **serverCommon**: Shared HTTP server code used by multiple server projects.
- **mp3PbCtxFactory**: MP3 codec support. Note: has `fork := true` explicitly set.

## OSGi

- Applications run in an OSGi container (tested with Apache Felix).
- Custom `OsgiImagePlugin` (in `project/OsgiImagePlugin.scala`) assembles bundles into runnable images.
- Most library projects enable `SbtOsgi` plugin. Check `build.sbt` for each project's OSGi settings.
- OSGi image template system: copy template dirs via `sourceImagePaths` setting. Hierarchical: base image first, specialized images overlay.
- System property `osgi.image.rootPath` controls template root (default: `.`).

## CI

- Only one workflow: `.github/workflows/player-server-native-image.yml` (manual dispatch for GraalVM native image builds).
- No automated CI for tests/lint. Run `sbt test` locally before committing.

## Gotchas

- `-Xfatal-warnings` means any deprecation warning breaks the build. Fix warnings, don't suppress.
- The `images/` directory contains OSGi deployment templates and shell scripts, not source code.
- Some dependencies (JGUIraffe, Scalaz, Pekko Http) lack OSGi metadata and need manual bundle conversion for deployment. This only matters for building OSGi images, not for `sbt test`.
- JavaFX is not in the JDK since Java 11. Running OSGi images requires a custom JDK+JavaFX image or `--module-path` flag. See `images/README.adoc` and `images/javaFxImage.sh`.
