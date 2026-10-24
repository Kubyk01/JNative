# JNative

**Ahead-of-Time Compiler from JVM Bytecode to Native Executable with Static Lifetime Management**

## Idea

JNative compiles JAR and CLASS files directly into native executables without a JVM or garbage collector. For many application patterns, object lifetimes can be determined statically, allowing us to manage memory deterministically -like in Rust- but on top of Java/Kotlin/Scala bytecode.

**The core idea:**

```text
.class/.jar → bytecode analysis → lifetime analysis → destructor insertion → LLVM IR → native executable
```

No JVM. No GC pauses. No runtime interpreter overhead.

## How It Works

1. **Dependency resolution** — Reads `.class` files, plain JARs, directory trees, and Spring Boot fat JARs. Nested JARs are indexed lazily.
2. **Reachability analysis** — Starts at the entry point and walks reachable bytecode. Resolves reflection targets when names are compile-time constants, applies GraalVM-compatible `META-INF/native-image/` metadata, and closes over superclass `<clinit>` chains, descriptor types, and interface implementations.
3. **Bytecode to SSA IR** — Converts the JVM stack machine into basic blocks and static single-assignment (SSA) form. Handles `JSR`/`RET`, try/catch, monitor enter/exit, and polymorphic signatures explicitly.
4. **Analysis pipeline** — Runs fixed-point interprocedural points-to/alias, escape, and lifetime analyses on a shared scheduler sized by `--cores`.
5. **Initialization scheduling** — Topologically sorts `<clinit>` bodies and `System.initPhase1/2/3` according to field-writer-before-reader dependencies. Cyclic groups use lazy-triggered initialization; independent groups at the same level can initialize in parallel at startup.
6. **Destructor insertion** — Inserts destructor calls at each allocation's last use. For heap-escaped sites, pairs cleanup with nulling the containing field so later overwrite instrumentation does not double-free.
7. **Optimizations** — Performs scalar replacement of stack-escaping objects, dead-destructor elimination, and destructor inlining while preserving cycle safety through `__jnative_free`'s static-range check and shutdown guard.
8. **LLVM IR generation** — Emits one `.ll` file per owner class and a shared `_module.ll` for globals, `main`, and the initializer driver. Files include only the type definitions, externs, and function declarations they reference.
9. **Native compile and link** — Compiles each C runtime, per-class C, and LLVM IR translation unit to ThinLTO bitcode in parallel, then links with `clang -flto=thin -fuse-ld=lld` and `--thinlto-jobs=<cores>`.

## Supported Languages

JNative operates on JVM bytecode and can target code produced by:

- Java
- Kotlin
- Scala
- Groovy

## Prerequisites

- **JDK 17+** to run the JNative CLI.
- **Clang** (primary) or **GCC** (fallback) to compile the C runtime and LLVM IR.

## Supported Target Platforms

The target is selected with `--target` and/or `--data-layout`, or auto-detected from `os.name` and `os.arch`.

| Family | Known target triples |
| --- | --- |
| x86-64 | `x86_64-pc-linux-gnu`, `x86_64-pc-windows-msvc`, `x86_64-apple-darwin`, `x86_64-unknown-freebsd` and variants |
| AArch64 / arm64 | `aarch64-unknown-linux-gnu`, `arm64-apple-darwin`, `aarch64-pc-windows-msvc` |
| i686 | `i686-pc-linux-gnu`, `i686-pc-windows-msvc`, `i386-*` |
| RISC-V | `riscv64-unknown-linux-gnu` |
| POWER | `powerpc64le-unknown-linux-gnu`, `powerpc64-unknown-linux-gnu` |
| s390x | `s390x-ibm-linux-gnu` |
| ARMv7 | `armv7-unknown-linux-gnueabihf` |

The target triple determines the default data layout. `--data-layout` overrides it independently for custom ABIs, such as a non-default code model, soft-float, or unusual pointer width.

## Usage

```bash
# Inspect a JAR or CLASS file
java -jar jnative.jar inspect app.jar
java -jar jnative.jar inspect --bytecode Main.class

# Build a native executable; infer the entry class from the JAR Main-Class manifest
java -jar jnative.jar analyze app.jar

# Choose the entry point and output, and dump analysis results
java -jar jnative.jar analyze app.jar \
    --entry com.example.Main \
    --output myapp \
    --alias --escape --lifetime --destructor

# Cross-compile for the Windows
java -jar jnative.jar analyze app.jar \
    --entry com.example.Main \
    --target x86_64-pc-windows-msvc \
    -O3

# Generate LLVM IR without compiling or linking
java -jar jnative.jar analyze app.jar --entry com.example.Main --no-compile
```

## Commands

### `inspect`

```bash
jnative inspect <file.jar|.class> [--bytecode]
```

Prints the class list, methods, and fields. `--bytecode` adds a per-method disassembly.

### `analyze`

```bash
jnative analyze <file.jar|dir|.class> [options]
```

Builds a native executable or, with `--no-compile`, generates LLVM IR only.

| Option | Description |
| --- | --- |
| `--entry` | Fully qualified entry class. For a JAR, defaults to the manifest's `Main-Class`. |
| `--method` | Entry method name (default: `main`). |
| `--descriptor` | Entry method descriptor (default: `([Ljava/lang/String;)V`). |
| `--output` | Output file. By default, the input filename with `.jar`/`.class` stripped, next to the input; appends `.exe` for Windows targets. For example, `Test.jar` becomes `Test` or `Test.exe`. |
| `--no-compile` | Generate LLVM IR only; do not invoke the compiler or linker. |
| `--include-system` | Include JDK classes and methods in analysis dumps. |
| `--classes` | Show reachable user classes and methods. |
| `--alias` | Show alias-analysis points-to sets. |
| `--escape` | Show per-site escape status. |
| `--lifetime` | Show destruction points. |
| `--destructor` | Show destructor insertion. |
| `--all` | Equivalent to `--classes --alias --escape --lifetime --destructor`. |
| `--classes-graph` | Print the call graph reachable from the entry point. |
| `--debug-name` | Restrict analysis output to items whose names contain this substring. |
| `--cores` | Maximum CPU cores. `0` (default) uses `Runtime.availableProcessors()`. Controls parallel analysis, C compilation, and `--thinlto-jobs`. |
| `-O` | Optimization level `0`–`3` (default: `2`), passed as `-O<level>` to IR-to-bitcode and ThinLTO linking. |
| `--target` | LLVM target triple; auto-detected from the build host if omitted. |
| `--data-layout` | Override the data layout derived from `--target`. |

## What Works

The following features are implemented, subject to application-specific constraints:

- **Language constructs:** classes, interfaces, inheritance, polymorphism, erased generics, lambdas, and method references.
- **Objects and fields:** object allocation, field access, static fields, and arrays including multidimensional arrays.
- **Synchronization:** `synchronized`, `wait`, `notify`, and `notifyAll`.
- **Exception handling:** `try`/`catch`/`finally` via `setjmp`/`longjmp` with typed catch dispatch. Exceptions carry diagnostic messages and are matched against `@__type_info_*` vtables, allowing a `catch (IOException)` to catch a runtime-created `FileNotFoundException`.
- **Strings and dynamic calls:** compact-string literals (LATIN1 or UTF-16BE according to content), `invokedynamic` for `LambdaMetafactory` and `StringConcatFactory`, and string concatenation.
- **Type operations:** `instanceof` and `checkcast`.
- **Reflection:** within the compile-time reachable closure:
    - `Class.forName` and `ClassLoader.loadClass` with constant class names.
    - `Class.getMethod`, `getDeclaredMethod`, `getConstructor`, `getDeclaredConstructor`, `getField`, `getDeclaredField`, and `newInstance`.
    - `Method.invoke` and `Constructor.newInstance`.
    - `MethodHandles.Lookup.findStatic`, `findVirtual`, `findSpecial`, `findConstructor`, `findGetter`, `findSetter`, `findStaticGetter`, `findStaticSetter`, `findVarHandle`, and `findStaticVarHandle`.
    - `jdk.internal.misc.Unsafe.objectFieldOffset` and `allocateInstance`.
- **GraalVM-compatible metadata:** supports `reflect-config.json`, `proxy-config.json`, `jni-config.json`, and `resource-config.json` under `META-INF/native-image/`, including `condition.typeReachable` filtering.
- **Spring Boot:** lazily indexes nested JARs under `BOOT-INF/lib/` and loads individual classes on demand.
- **Concurrency:** `java.lang.Thread` runs on real pthreads, with support for `start`, `join`, `interrupt`, `park`/`unpark`, daemon and priority flags, thread names, context class loaders, and stack-trace-style thread enumeration.
- **Array copying:** `System.arraycopy`, including typed fast paths for `byte[]`, `char[]`, and `int[]`.
- **Compression and checksums:** `java.util.zip.Inflater` through a self-contained DEFLATE decoder without a `libz` dependency, and `java.util.zip.CRC32`.
- **Other runtime APIs:** `SecureRandom` (SHA1PRNG via `sun.security.provider.SecureRandom`), `TimeZone` (offset-form ID fallback), `MappedMemoryUtils`, and `jdk.internal.perf.Perf`.
- **File I/O:** regular file I/O through per-class C overrides for `FileInputStream`, `FileOutputStream`, `RandomAccessFile`, and `UnixFileSystem`.
- **Networking:** `Inet4Address`, `Inet6Address`, `InetAddress`, and `NetworkInterface`, including interface enumeration and reverse lookups.
- **Reflection mirrors:** `Class.getDeclaredMethods0`, `getDeclaredFields0`, and `getDeclaredConstructors0` construct real `Field`, `Method`, and `Constructor` mirrors with populated `parameterTypes`, `exceptionTypes`, and root fields, supporting `Executable.sharedToString` and `ReflectionData` without null-pointer failures.
- **Resources and time zones:** embeds every non-`.class` resource in the `java.base` module and the build JDK's `lib/tzdb.dat`, served through `Class.getResourceAsStream` and `FileInputStream` for `/lib/tzdb.dat`.
- **Shutdown cleanup:** cycle-safe destruction uses a freed-address set and per-thread claim stack to detect doubly reachable objects and destructor cycles during `atexit` cleanup.
- **Destructor fast/slow paths:** outside shutdown, destructors use a direct load-check-free call; during shutdown, they use a cycle-safe slow path.
- **Core system APIs:** `System.out`, `System.err`, `System.in`, `System.getProperties`, `System.setProperty`, `System.currentTimeMillis`, `System.nanoTime`, `System.identityHashCode`, and `System.exit`.

The produced executable does not require a JDK, JVM, or `libz` at runtime. Target platform system libraries and runtime ABI requirements still apply.

## Memory Management Model

JNative uses static escape and lifetime analysis to classify each allocation site:

| Classification | Meaning and cleanup behavior |
| --- | --- |
| `STACK` | The object does not escape its defining function. It can use stack allocation when no opaque use prevents it; scalar replacement can eliminate it entirely when its fields are the only observable state. |
| `HEAP` | The object is stored in another object's field. A destructor is inserted at its last use, with the containing field nulled. Field-overwrite instrumentation also releases the previous value at `PUT_FIELD` when liveness analysis proves no other reference observes it. |
| `RETURN` | The object is returned to the caller; the caller owns it. |
| `GLOBAL` | The object is reachable from a static field and is released during the shutdown pass only. |
| `THREAD` | The object is handed to another thread and is not released per-use. |
| `UNKNOWN` | The analysis cannot classify the site, so cleanup is handled conservatively. |

## Building

```bash
mvn clean package
java -jar target/JNative-0.1.jar ...
```

## License

Apache 2.0

*JNative is an experimental AOT compiler. Use with caution in production.*
