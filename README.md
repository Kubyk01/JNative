# JNative

**Ahead-of-Time Compiler from JVM Bytecode to Native Executable with Static Lifetime Management**

## Idea

JNative compiles JAR and CLASS files directly into native executables without a JVM or garbage collector. The key insight: for many application patterns, object lifetimes can be determined statically, allowing us to manage memory deterministically—like in Rust—but on top of Java/Kotlin/Scala bytecode.

**The core idea:**

```text
.class/.jar → bytecode analysis → lifetime analysis → destructor insertion → LLVM IR → native executable
```

No JVM. No GC pauses. No runtime overhead.

## How It Works

1. **Reachability Analysis** — Starting from the entry point (`main`), builds a call graph, keeping only reachable code.
2. **Bytecode to SSA IR** — Converts JVM stack-based bytecode into SSA form with basic blocks and control flow.
3. **Alias Analysis** — Determines when two references point to the same object.
4. **Escape Analysis** — Determines whether an object outlives its allocating function.
5. **Lifetime Analysis** — For each allocation, finds the last point where the object is used, and inserts a destructor call there.
6. **Optimization** — Removes unnecessary allocations, inlines destructors, and performs scalar replacement.
7. **LLVM IR Generation** — Produces LLVM IR that compiles to native code.

## Supported Languages

Since it works at the bytecode level, JNative supports **any JVM language**:

- Java
- Kotlin
- Scala
- Groovy
- Clojure (limited)

## Usage

```bash
# Inspect a JAR or CLASS file
java -jar jnative.jar inspect app.jar
java -jar jnative.jar inspect --bytecode Main.class

# Build a native executable
java -jar jnative.jar analyze app.jar --entry com.example.Main

# With additional options
java -jar jnative.jar analyze app.jar --entry com.example.Main \
    --output app \
    --show-escape \
    --show-lifetime

# Don't compile (just generate LLVM IR)
java -jar jnative.jar analyze app.jar --entry com.example.Main --no-compile
```

## Commands

### `inspect`

```bash
jnative inspect <file.jar|.class> [--bytecode]
```

Shows information about a JAR or CLASS file:

- Classes found
- Methods and fields
- Bytecode (with `--bytecode`)

### `analyze`

```bash
jnative analyze <file.jar|dir> --entry <class> [options]
```

Builds a native executable:

| Option | Description |
| --- | --- |
| `--entry` | Fully qualified entry class |
| `--method` | Entry method name (default: `main`) |
| `--descriptor` | Method descriptor (default: `([Ljava/lang/String;)V`) |
| `--output` | Output file name (default: `a.out`) |
| `--no-compile` | Only generate LLVM IR |
| `--include-system` | Include system classes in output |
| `--show-classes` | Show reachable classes |
| `--show-alias` | Show alias analysis results |
| `--show-escape` | Show escape analysis results |
| `--show-lifetime` | Show lifetime analysis results |
| `--show-destructor` | Show destructor insertion |
| `--debug-name` | Filter output for a specific class/method |

## What Works

- Core Java language constructs
- Basic control flow (`if`, `while`, `for`, `switch`)
- Object allocation and field access
- Method calls (static, virtual, interface)
- Inheritance and polymorphism (via vtables)
- Exception handling (with `setjmp`/`longjmp`)
- Arrays
- String constants
- Synchronization (`synchronized` via pthread mutexes)
- Threads (basic support)
- Static fields

## Limitations (Current Version)

JNative is a **research prototype** with important limitations:

1. **Limited Reflection** — `Class.forName()` only works when the class name is a compile-time constant.
2. **No JNI** — Native methods must be provided through the JNative runtime API or are unsupported.
3. **Runtime Class Loading** — No `ClassLoader` support for dynamically loading code.
4. **Single-threaded** in the first version—multiple threads are supported but not fully tested.
5. **Limited JDK Coverage** — Only a subset of `java.lang.*` and `java.util.*` is supported.
6. **No Serialization** — No `java.io.Serializable` support.
7. **No AWT/Swing** — GUI frameworks not supported.

Some of this feature maybe will be added in the future.

## Memory Management Model

JNative's memory management is **deterministic** and **compile-time**:

- **Stack allocation** — objects that never escape are allocated on the stack and live on the stack.
- **Heap allocation** — objects that may escape are allocated on the heap.
- **Static allocation** — static fields are initialized at program startup.
- **Destructors** — are inserted at the last use point of an object.
- **No GC** — no garbage collection, no GC pauses, no GC overhead.

This model is based on **ownership and lifetime analysis**, similar to Rust, but inferred automatically from Java bytecode.

## Technical Architecture

```text
app.jar
   │
   ▼
Dependency Resolution
   │
   ▼
Reachability Analysis
   │
   ▼
Bytecode → SSA IR
   │
   ├── Alias Analysis
   ├── Escape Analysis
   └── Lifetime Analysis
   │
   ▼
Destructor Insertion
   │
   ▼
Optimization
   │
   ▼
LLVM IR
   │
   ▼
Compile & Link
   │
   ▼
Native Executable
```

## Building

```bash
mvn clean package
java -jar target/JNative-0.1.jar ...
```

## License

Apache 2.0

*JNative is an experimental AOT compiler. Use with caution in production.*
