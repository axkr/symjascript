# symjascript

A command-line interpreter for [Symja](https://github.com/axkr/symja_android_library), the Java
computer algebra system with a syntax close to Mathematica's. Its command line is modelled on
Wolfram's [`wolframscript`](https://reference.wolfram.com/language/ref/program/wolframscript.html):
the same options run code, scripts and functions, and a `-wstp` kernel mode lets the
[WLJS Notebook](https://github.com/WLJSTeam/wljs-notebook) drive Symja as its kernel.

It ships in two builds from the same source:

| build | runtime | start-up | raster output (`-format PNG`, `Image[]`) |
|---|---|---|---|
| jar + launcher scripts | any Java 11+ | ~1 s | yes |
| GraalVM native binary | none | ~0.3 s | Linux, Windows; **not macOS** |

This README is for developers: how to build every variant, install it, and what each argument
does. The end-user READMEs that ship inside the zips are
[`src/dist/README.txt`](matheclipse-console/src/dist/README.txt) and
[`src/dist/native/README.txt`](matheclipse-console/src/dist/native/README.txt).

- [Repository layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [The Symja dependency](#the-symja-dependency)
- [Building](#building)
- [Installing](#installing)
- [Usage](#usage)
- [Testing](#testing)
- [WLJS Notebook](#wljs-notebook)
- [Releasing](#releasing)
- [Known limitations](#known-limitations)

## Repository layout

```
README.md                              this file
matheclipse-console/                   the Maven project (standalone, no <parent>)
  pom.xml
  src/main/java/org/matheclipse/console/
    SymjaScript.java                   entry point: options, modes, REPL
    SymjaCompleter.java                TAB completion
    SymjaHighlighter.java              REPL syntax colouring
    Rasterizer.java                    SVG -> PNG/JPEG/...; the only AWT code
  src/main/resources/META-INF/native-image/org.matheclipse/matheclipse-console/
    reachability-metadata.json         JLine terminal providers for the native image
  src/assembly/
    distribution.xml                   the portable zip
    native-distribution.xml            the native zip
  src/dist/
    bin/symjascript(.bat), bin/symja(.bat)   launchers (symja = alias)
    README.txt, native/README.txt, LICENSE-*.txt
  src/test/
    java/.../SymjaScriptTest.java      JUnit tests of the command line
    pty/                               terminal harnesses (ptyrun.py, ptytype.py)
    wljs/                              start scripts for manual WLJS Notebook tests
```

The Maven artifact is still called `org.matheclipse:matheclipse-console`; the program, the
launchers and the zips are called `symjascript`.

## Prerequisites

| to build | you need |
|---|---|
| jar, zip, launcher | **JDK 17 or newer** and **Maven 3.9** |
| native binary | additionally **GraalVM for JDK 21 or newer** (developed on Oracle GraalVM 25) |

- The code is compiled to **Java 11 bytecode** (`maven.compiler.release` 11), so the jar runs on
  any Java 11+. Building needs 17+ because the JUnit 6 test engine does.
- The native build runs `native-image` from the JDK Maven runs on, so `JAVA_HOME` must point at
  GraalVM. It does not have to be your default JDK:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 25)     # macOS
  export JAVA_HOME=/path/to/graalvm-jdk-25             # Linux, Windows (Git Bash)
  ```
- `native-image` needs the platform's C toolchain: the Xcode command line tools on macOS, `gcc`
  with the glibc and zlib development headers on Linux, and the Visual Studio Build Tools on
  Windows. The GraalVM installation notes for your platform have the details.
- The native build needs a lot of memory. The builder heap defaults to **24 GB** and ran out at
  8 GB; lower it with `-Dnative.build.maxheap=12g` if your machine has less, at the risk of an
  `OutOfMemoryError` in the builder.

## The Symja dependency

`symjascript` depends on three Symja modules at version `${symja.version}` (`3.3.0-SNAPSHOT`):
`matheclipse-core`, `matheclipse-image` and `matheclipse-graphtheory`. The pom declares two
snapshot repositories to resolve them without a local Symja checkout:

- `central.sonatype.com/repository/maven-snapshots` for the Symja snapshots
- `packages.orekit.org/repository/maven-snapshots` for `hipparchus 4.1-SNAPSHOT`, which Symja
  needs and which only lives there

**The console often needs Symja changes that are not published yet.** When that happens the
build fails to compile with "cannot find symbol" against a Symja class. Build the Symja modules
from source into your local repository first:

```bash
git clone https://github.com/axkr/symja_android_library.git
git clone https://github.com/axkr/symjascript.git

cd symja_android_library/symja_android_library
mvn -pl matheclipse-core,matheclipse-image,matheclipse-graphtheory -am install -DskipTests
```

`-am` also builds the modules those three need (parser, external, logging, gpl, script). Keep
the two clones side by side: the WLJS scripts expect `symja_android_library` next to this
repository.

Two traps when working against a local Symja:

- **Stale jars in `~/.m2`.** Maven prefers what is installed locally, so after pulling Symja
  changes, install again before building the console. Otherwise the console compiles and runs
  against the old core.
- **Regenerated symbol tables need a clean build.** Adding a built-in symbol renumbers the
  constants in `matheclipse-core`'s `ID.java`, and those constants are compiled into every
  module that uses them. After such a change install with `clean`, and include image and
  graphtheory:
  ```bash
  mvn -pl matheclipse-core,matheclipse-image,matheclipse-graphtheory -am clean install -DskipTests
  ```
  An incremental build leaves the dependent modules pointing at the wrong symbols.

## Building

Run all builds from `matheclipse-console/`.

### Jar, portable zip and development launcher

```bash
cd symjascript/matheclipse-console
mvn package
```

This compiles, runs the tests and produces:

| file in `target/` | size | what it is |
|---|---|---|
| `matheclipse-console-3.3.0-SNAPSHOT.jar` | 35 KB | the console classes only |
| `matheclipse-console-3.3.0-SNAPSHOT-all.jar` | 48 MB | **uber jar**: every dependency, `java -jar` runnable |
| `symjascript-3.3.0-SNAPSHOT.zip` | 44 MB | **portable distribution** (see [Installing](#installing)) |
| `appassembler/bin/symjascript` (+ `.bat`) | | **development launcher** over `appassembler/lib/*.jar` |

- Add `-DskipTests` to skip the tests, and `-o` to build offline once `~/.m2` is filled.
- The uber jar is built by `maven-shade-plugin`, which also:
  - drops the signature files of signed dependencies (otherwise the jar fails with "Invalid
    signature file digest");
  - merges the `META-INF/services` files JLine uses to find its terminal backends;
  - writes `Multi-Release: true` and `Enable-Native-Access: ALL-UNNAMED` into the manifest. The
    latter silences the Java 24+ restricted-method warnings for JLine's native library. It is a
    manifest entry rather than a launcher flag because Java 11 ignores an unknown manifest
    attribute but refuses to start on an unknown flag.
- The portable zip is `maven-assembly-plugin` with `src/assembly/distribution.xml`. It renames
  the uber jar to `lib/symjascript.jar`, so the launchers need not know the version.
- `appassembler-maven-plugin` never deletes old files. If you see `appassembler/bin/symja`
  launchers pointing at `MMAConsole`, or duplicate jar versions in `appassembler/lib`, run
  `mvn clean package`.

### Native binary

```bash
export JAVA_HOME=/path/to/graalvm          # see Prerequisites
mvn -Pnative package -DskipTests          # binary only
mvn -Pnative verify -DskipTests           # binary and its zip
```

| file in `target/` | size | built by |
|---|---|---|
| `symjascript` | ~170 MB | `-Pnative package` |
| `symjascript-3.3.0-SNAPSHOT-<os>-<arch>.zip` | ~66 MB | `-Pnative verify` |

The `native` profile also builds everything from the jar build. A native build takes about
5 minutes.

- **Use `verify` for the zip.** The base build already declares `maven-assembly-plugin` for the
  portable zip, so an assembly execution added in the profile at `package` would run *before*
  `native-maven-plugin` and try to zip a binary that does not exist yet. The native zip is bound
  to `verify` instead.
- The zip name carries the platform from `os-maven-plugin` (`${os.detected.classifier}`, e.g.
  `osx-aarch_64`, `linux-x86_64`), which is the naming JReleaser expects.
- **native-image does not cross-compile.** The binary runs only on the OS and CPU it was built
  on, so each platform needs its own build. For macOS on both CPUs, build twice and join the
  results with `lipo -create`.
- `native-maven-plugin` is 1.x. The 0.10.x plugins look for GraalVM's removed `gu` tool and stop
  with "not a GraalVM distribution".
- Build arguments: `--no-fallback`, `-J-Xmx${native.build.maxheap}`, and
  `--enable-native-access=ALL-UNNAMED`. The native counterpart of the manifest entry has to be
  given at build time; passing it to the binary does nothing.

### Native-image metadata

Reflection and resources that native-image cannot discover are declared in
`reachability-metadata.json` files, next to the code that needs them:

| module | file | registers |
|---|---|---|
| console | `src/main/resources/META-INF/native-image/org.matheclipse/matheclipse-console/` | JLine and Jansi terminal providers, native libraries, capability files |
| matheclipse-core | `.../org.matheclipse/matheclipse-core/` | rule classes loaded by name, apfloat builder factories, resources such as documentation, units and colour data |
| matheclipse-graphtheory | `.../org.matheclipse/matheclipse-graphtheory/` | the edge constructors JGraphT creates reflectively |

Libraries covered by the GraalVM reachability metadata repository are handled automatically
(`metadataRepository` is enabled). When a function works in the jar but not in the native
binary, a missing entry is the usual cause: a `ClassNotFoundException` or a
`NoSuchMethodException ... <init>()` names what to add. Recording a session with the tracing agent
produces a starting point. Run it with GraalVM's `java`, which provides the agent:

```bash
java -agentlib:native-image-agent=config-output-dir=/tmp/ni-config \
     -jar target/matheclipse-console-3.3.0-SNAPSHOT-all.jar
```

## Installing

### Portable distribution (any OS with Java 11+)

```bash
unzip target/symjascript-3.3.0-SNAPSHOT.zip -d ~/opt
ln -s ~/opt/symjascript-3.3.0-SNAPSHOT/bin/symjascript ~/bin/symjascript   # or add bin/ to PATH
symjascript -version
```

```
symjascript-3.3.0-SNAPSHOT/
  bin/symjascript  bin/symjascript.bat   launchers
  bin/symja        bin/symja.bat         aliases for the old name
  lib/symjascript.jar                    the uber jar
  README.txt  LICENSE-GPL.txt  LICENSE-LGPL.txt
```

- `bin/` must stay next to `lib/`: the launcher finds the jar relative to itself and follows a
  symlink back to the distribution.
- It uses `$JAVA_HOME/bin/java` if `JAVA_HOME` is set, otherwise `java` from the `PATH`, and
  passes `JAVA_OPTS` to the JVM: `JAVA_OPTS=-Xmx4g symjascript`.
- Without the launcher: `java -jar lib/symjascript.jar`.

### Native binary

```bash
unzip target/symjascript-3.3.0-SNAPSHOT-osx-aarch_64.zip -d /tmp
cp /tmp/symjascript-3.3.0-SNAPSHOT-osx-aarch_64/bin/symjascript /usr/local/bin/
```

- The binary depends on nothing next to it, so copying it alone is enough.
- On macOS the binary is not notarized. Clear the quarantine flag of a downloaded copy with
  `xattr -d com.apple.quarantine /usr/local/bin/symjascript`.

### During development

`target/appassembler/bin/symjascript` runs the classes and dependency jars straight out of
`target/`, with no zip in between. The WLJS start scripts use it. To make it your `symjascript`:

```bash
ln -s "$PWD/target/appassembler/bin/symjascript" ~/bin/symjascript
```

## Usage

### Modes

| command | mode |
|---|---|
| `symjascript` | interactive session (JLine: history, TAB completion, highlighting) |
| `symjascript -code '<code>'` | evaluate once and print the result |
| `symjascript -f script.wls [args...]` | run a script; `-file`, `-script` and a bare file name work too |
| `./script.wls [args...]` | run a script through its `#!` line |
| `symjascript -function f -args 1 2` | evaluate `f[1, 2]` |
| `symjascript < program.wls` | no work asked for and stdin is not a terminal: run stdin as a script |
| `symjascript -wstp` | kernel link: read packets on stdin, answer on stdout |

A script is parsed completely, then run statement by statement. Scripts, stdin programs and
kernel links run in wolframscript compatibility mode: `$VersionNumber` reports the Wolfram
Language version Symja follows (`14.1`), so version checks written for wolframscript pass.
`$Version` still starts with `Symja`, which is how a script tells the kernels apart.

### Options

| option | argument | description |
|---|---|---|
| `-c`, `-code` | `code` | evaluate the code and print its result |
| `-f`, `-file`, `-script` | `file` | run a script file; a `#!` first line is ignored |
| *(bare)* | `file` | the first token that is neither an option nor an option's value, if it names a file, is the script |
| `-fun`, `-function` | `f` | evaluate `f[args]` with the `-args` values |
| `-s`, `-signature` | `type...` | types of the `-args` values: `String` quotes the value; `Integer`, `Real`, `Number`, `Expression` pass it as written |
| `-a`, `-args` | `value...` | every following token is a value for `-function` |
| `--` | | with `-function`: same as `-args`; after a script file: the rest goes to the script unchanged |
| `-format` | `type` | write the result to stdout in another format (below) |
| `-charset` | `encoding` | encoding for output, the script and `-linewise` input |
| `-linewise` | | run the code or script once per stdin line (below) |
| `-print` | `[all]` | for scripts and stdin programs: print the last result, or every result |
| `-timeout` | `seconds [value]` | limit each evaluation (below) |
| `-initfile` | `file` | evaluate a file after the standard start-up files; repeatable |
| `-noinit` | | skip the standard start-up files |
| `-d`, `-default` | `file` | older spelling of `-initfile` |
| `-l`, `-local` | `[kernelpath]` | accepted and ignored; symjascript always evaluates locally |
| `-v`, `-verbose` | | report on stderr which start-up files were loaded |
| `-wstp`, `-mathlink` | | kernel-link mode; only when given before the script file |
| `-version` | | print `symjascript <version>` and exit |
| `-h`, `-help` | | print the options and exit |

wolframscript's cloud and WSTPServer options (`-cloud`/`-o`, `-api`, `-wstpserver`,
`-startprofile`, `-continueprofile`, `-kernelid`, `-kernelpool`, `-auth`/`-authenticate`,
`-username`, `-password`, `-permissionskey`, `-entitlement`, `-listwstpservers`, `-disconnect`)
are recognised and refused with a message and exit status 1.

### Option order and script arguments

As in wolframscript, symjascript recognises its options **wherever they appear, also after the
script file**. Every token it does not recognise, dashes or not, becomes an argument of the
script:

```bash
symjascript -f report.wls -print all 2026 --verbose
#          script ^^^^^^  ^^^^^^^^^ option  ^^^^^^^^^^^^^ script arguments
```

- `--` after the script file ends option parsing: `symjascript -f report.wls -- -print` gives
  the script the argument `-print`.
- `-wstp` and `-mathlink` start a kernel link only **before** the script file. After it they are
  ordinary arguments, so `symjascript -f server.wls -wstp` runs the script.
- An option with an optional value takes the next token unless it starts with `-`: after a
  script, `-timeout 5 2026` uses `2026` as the timeout value.
- Before the script, an unknown option is an error (exit 1).

### What a script sees

| variable | value |
|---|---|
| `$ScriptCommandLine` | `{script, args...}`: the script as typed, then its arguments, never symjascript's options. `{}` outside a script. |
| `$CommandLine` | `{executable, all raw arguments...}`. The executable is the running binary for the native build and the `java` command for the jar. Override it with `-Dsymja.executable=...` in `JAVA_OPTS`. |
| `$InputFileName` | absolute path of the running script (not for stdin, `-code` or `-linewise`) |
| `$Input` | the script name as typed |
| `$ScriptInputString` | the current line under `-linewise`, otherwise `None` |
| `$ScriptLine` | the same line; the older name |
| `$VersionNumber` | `14.1` in scripts, stdin programs and kernel links; Symja's own number with `-code` |

A script locates its own directory the Wolfram way:
`SetDirectory[DirectoryName[$InputFileName]]`.

### Shebang scripts

```bash
#!/usr/bin/env -S symjascript -f
Print["Hello from ", First[$ScriptCommandLine]];
```

Make the file executable with `chmod +x` and run it as `./hello.wls`. Use `env -S`: Linux passes
everything after the interpreter as one argument, so without `-S` `env` looks for a program
called `symjascript -f`. macOS splits the arguments either way. An absolute interpreter path
(`#!/usr/local/bin/symjascript -f`) works everywhere without `-S`.

### Printing

- `-code` and `-function` always print their result. `-print` does not change them.
- A script or stdin program prints nothing but what it `Print`s, unless `-print` (last result)
  or `-print all` (every statement's result) is given. A statement ending in `;` has no result.
- A graphic prints as `-Graphics-` / `-Graphics3D-`. Use `-format` to get the picture.

### `-timeout seconds [value]`

Limits each evaluation to `seconds`: the `-code`/`-function` expression, or **each top-level
statement** of a script or stdin program. When the limit is reached, that evaluation gives
`value`, or `$TimedOut` if no value was given. Output printed before the limit stays printed, and
a script continues with its next statement. The exit status is not changed.

```bash
symjascript -timeout 2 -code 'Pause[5]; 1'       # $TimedOut
symjascript -timeout 2 0 -code 'Pause[5]; 1'     # 0
```

`Abort[]` still gives `$Aborted`, and `Exit[n]` still ends the process with `n`. In the REPL,
`/timeouton` sets a 60 s limit and `/timeoutoff` removes it.

### `-linewise`

Runs the `-code` or `-file` program once for every line of stdin, with the line (without its line
end) in `$ScriptInputString` and `$ScriptLine`:

```bash
printf 'ab\ncd\n' | symjascript -code 'StringReverse[$ScriptInputString]' -linewise
# ba
# dc
```

### `-format`

Writes the result to stdout in the given format (case-insensitive), for redirection into a file:

```bash
symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG > plot.svg
```

| kind | formats | notes |
|---|---|---|
| text | `SVG`, `Base64`, `ExpressionJSON`, `Table` | produced by `ExportString`; identical in every build |
| raster | `PNG`, `JPEG`/`JPG`, `GIF`, `BMP`, `TIFF`/`TIF`, `PNM` | the SVG is rendered with jsvg and encoded with ImageIO; **needs AWT** |

- An SVG is written as a standalone document, with an XML declaration and without the
  responsive root style that only suits embedding in HTML.
- A result that is not a graphic, an unsupported format, or a missing AWT gives a message on
  stderr, nothing on stdout, and exit status 1.
- With `-format`, `Print` output from the program and from start-up files goes to stderr, so it
  cannot corrupt the file.

### Exit status

| situation | status |
|---|---|
| normal end, including after syntax errors and messages | `0` |
| `Exit[n]` / `Quit[n]` | `n` |
| unknown option, missing script or init file, bad `-timeout`, unsupported `-format`, result that cannot be exported | `1` |
| a start-up file failed (the others still run) | `1` |

This follows wolframscript, which "will typically return a zero exit code … unless `Exit[n]` is
used", and mathicsscript, whose tests assert exit 0 after a syntax error. A script that must
signal failure calls `Exit[1]`.

### Output streams

- Results go to stdout; messages, syntax errors and symjascript's diagnostics go to stderr.
- A one-shot run (`-code`, a script, `-function`, `-format`, stdin programs) writes through the
  process's own streams, so shell redirection works. The interactive session writes through
  JLine's terminal instead.
- A kernel link owns stdout for its frames; anything Java prints goes to stderr.

### Start-up files

Unless `-noinit` is given, these are evaluated before anything else, in this order:

1. `$BaseDirectory/Kernel/init.m`: installation-wide.
2. `$UserBaseDirectory/Kernel/init.m`: per user, read second so it can override the first.
3. `init.m` and `Kernel/init.m` of every application directory below `Autoload/` in both base
   directories, sorted by name.
4. The files named by `-initfile`, in command-line order.

| directory | default | override |
|---|---|---|
| `$BaseDirectory` | `~/Symja` | `SYMJA_BASE_DIRECTORY` |
| `$UserBaseDirectory` | `~/Library/Symja` (macOS), `%APPDATA%\Symja` (Windows), `~/.Symja` (others) | `SYMJA_USER_BASE_DIRECTORY` |

Both base directories and every autoloaded application are on `$Path`, so `Get` and `Needs` find
packages there. A failing file is reported, skipped, and makes the exit status 1. A kernel
started with `-wstp` reads no start-up files.

### Interactive session

- TAB completes built-in function names; `?Name` shows help.
- Brackets left open are closed automatically.
- Commands (at least four characters, starting with `/`):
  - `/exit`
  - `/timeoutoff`, `/timeouton`
  - output forms: `/java`, `/input`, `/output`, `/pretty` (2D ASCII), `/traditional`
- Ctrl-C at the prompt is ignored, Ctrl-D quits.

### Security

symjascript is a local tool with the rights of the user who runs it. It enables Symja's file
system access (`Config.FILESYSTEM_ENABLED`) and operating-system access
(`Config.OS_ACCESS_ENABLED`: processes, sockets, kernel links), allows `Exit[]` to end the
process, and evaluates start-up files automatically. **Never run untrusted code or feed it input
you do not trust.** For a sandboxed evaluator, embed `matheclipse-core` with those settings off.

## Testing

```bash
mvn test                                   # JUnit, ~5 s
mvn test -Dtest=SymjaScriptTest#anExpiredTimeoutPrintsTimedOut
```

`SymjaScriptTest` drives `SymjaScript.runConsole(String[], PrintWriter, PrintWriter)`, which is
`main` without `System.exit`. It covers options, argument order, exit status, `-timeout`,
`-linewise` and the script variables. Build the Symja modules first if the console depends on
unpublished changes ([above](#the-symja-dependency)).

### Terminal harnesses

Two behaviours only exist when stdin is a terminal, and a piped test cannot reach them:

- **Output routing.** With a terminal on stdin, JLine builds a system terminal whose writer
  bypasses shell redirection. `symjascript -code ... -format SVG > plot.svg` once wrote an empty
  file this way, while every piped test passed.
- **TAB completion.** It is not active without a terminal.

`src/test/pty/` has two small Python harnesses that run the program on a pseudo-terminal:

```bash
python3 src/test/pty/ptyrun.py /tmp/out.svg target/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG
python3 src/test/pty/ptytype.py 'Integrat\t[' target/symjascript     # expect: In[1]:= Integrate[]
```

`target/symjascript` is the native binary; use `target/appassembler/bin/symjascript` for the JVM
build. Details are in [`src/test/pty/README.md`](matheclipse-console/src/test/pty/README.md).

### Native binary

The JUnit tests run on the JVM only. After changing anything that could reach reflection,
resources or AWT, build the native binary and repeat the checks by hand, for example:

```bash
target/symjascript -code 'Integrate[Sin[x]*Exp[x],x]'
target/symjascript -code 'EdgeCount[Graph[{1->2,2->3}]]'
target/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG > /tmp/p.svg
```

## WLJS Notebook

[WLJS Notebook](https://github.com/WLJSTeam/wljs-notebook) is a Wolfram Language notebook
front end. Its `main` branch has a Symja socket adapter (`Packages/CSockets/Kernel/Symja.wl`),
chosen when `$Version` starts with `Symja`. `symjascript` provides both kernels it needs:

- a **master kernel** running the notebook server: `symjascript -script Scripts/start.wls`
- an **evaluation kernel** the master starts over a link: `symjascript -wstp`

To test it by hand, check out the repositories side by side:

```
git/
  symja_android_library/
  symjascript/              this repository
  wljs-notebook/            WLJSTeam/wljs-notebook, main
```

```bash
matheclipse-console/src/test/wljs/start-wljs.sh --build --open    # macOS, Linux
matheclipse-console\src\test\wljs\start-wljs.cmd --build          # Windows
```

- `--build` installs the Symja modules offline (`mvn -o`, so `~/.m2` must already be filled)
  and repackages the console.
- `--open` opens the browser once the notebook is up.
- It runs the JVM launcher `target/appassembler/bin/symjascript`.
- Other checkout locations: `SYMJA_DIR`, `WLJS_NOTEBOOK_DIR`.
- Start-up takes about 90 s; the page is at `http://127.0.0.1:20560`. Ports 20560 and 20559 must be
  free.
- The log is `target/wljs-notebook.log`.
- Ctrl-C stops both kernels.

Probing and troubleshooting are described in
[`src/test/wljs/README.md`](matheclipse-console/src/test/wljs/README.md). The notebook is
deliberately not part of the Maven build: it is a long-running server, and it needs a checkout the
build knows nothing about.

## Releasing

Releases use [JReleaser](https://jreleaser.org) 1.12, configured in the pom. It is not bound to a
lifecycle phase, so its goals are called explicitly:

```bash
mvn package                                              # the uber jar must exist
JRELEASER_GITHUB_TOKEN=dummy mvn jreleaser:config -Djreleaser.dry.run=true   # validate
JRELEASER_GITHUB_TOKEN=<token> mvn jreleaser:full-release                   # publish
```

- **Distribution `symja`** (`SINGLE_JAR`) ships the uber jar and feeds the Homebrew tap
  `axkr/homebrew-tap` and the Scoop bucket `axkr/scoop-bucket`.
- **Distribution `symja-native`** (`BINARY`, `native` profile only) ships the platform zip. brew
  and scoop stay on the jar: a native formula needs one binary per platform, which means a build
  matrix.
- The GitHub release goes to `axkr/symjascript`.
- `gitRootSearch` is enabled because `pom.xml` sits one directory below `.git`. Without it every
  JReleaser goal stops with "Could not determine git HEAD".

## Known limitations

- **No AWT in the macOS native binary** ([oracle/graal#13272](https://github.com/oracle/graal/issues/13272)).
  Raster `-format`s report that AWT is missing and exit 1. `Image[...]` and the other image
  functions print a `noawt` message and stay unevaluated. SVG, graphs and all other functions
  work. Linux and Windows native images support AWT.
- **One native build per platform**: native-image does not cross-compile.
- **Raster formats are limited to the ImageIO writers on the classpath.** WebP, TGA, ICO and PSD
  can be imported through `matheclipse-image`'s plug-ins, but not written.
- **`-format` covers the text formats `ExportString` implements**: SVG, Base64, ExpressionJSON,
  Table. CSV and TSV need `matheclipse-dataset`, which the console does not include.
- **An optional value is taken greedily**: `-print all` and `-timeout s value` take the next
  token when it does not start with `-`, even after the script file.
- **The kernel link is not WSTP.** `-wstp` speaks Symja's own framing (a type byte, a length and a
  WXF or text payload), which the WLJS adapter understands. Wolfram's WSTP libraries cannot
  connect to it.
- **`$CommandLine` for the jar starts with the `java` command**, not with a launcher script,
  unless `-Dsymja.executable` is set.

## License

symjascript is distributed under the GNU General Public License v3. The `matheclipse-parser`,
`matheclipse-core` and `matheclipse-external` modules it builds on are published under the GNU
Lesser General Public License v3. The license texts are in
[`matheclipse-console/src/dist/`](matheclipse-console/src/dist/).
