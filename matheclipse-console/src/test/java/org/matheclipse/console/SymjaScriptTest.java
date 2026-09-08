package org.matheclipse.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line of <code>symjascript</code>: how work is asked for, what a script sees of it and
 * what the process ends with.
 *
 * <p>
 * These drive {@link SymjaScript#runConsole(String[], PrintWriter, PrintWriter)}, which is
 * {@code main} without the {@link System#exit(int)}, and read what a script printed off
 * {@code System.out} - a script speaks through {@code Print[]}, which the engine writes there.
 */
public class SymjaScriptTest {

  /** What one console run produced. */
  private static class Run {
    final int status;
    final String out;

    Run(int status, String out) {
      this.status = status;
      this.out = out;
    }
  }

  private static Run run(String... args) {
    return runWithStdin("", args);
  }

  private static Run runWithStdin(String stdin, String... args) {
    PrintStream originalOut = System.out;
    InputStream originalIn = System.in;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    StringWriter consoleOut = new StringWriter();
    StringWriter consoleErr = new StringWriter();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
      int status = SymjaScript.runConsole(args, new PrintWriter(consoleOut, true),
          new PrintWriter(consoleErr, true));
      return new Run(status, captured.toString(StandardCharsets.UTF_8) + consoleOut);
    } finally {
      System.setOut(originalOut);
      System.setIn(originalIn);
    }
  }

  private static Path script(Path directory, String name, String source) throws IOException {
    Path file = directory.resolve(name);
    Files.write(file, source.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  @Test
  public void exitEndsTheProcessWithItsCode() {
    assertEquals(3, run("-code", "Exit[3]").status);
    assertEquals(0, run("-code", "Exit[]").status);
  }

  @Test
  public void exitInsideAScriptStopsIt(@TempDir Path directory) throws IOException {
    Path file = script(directory, "exit.wls", "Print[\"before\"];\nExit[4];\nPrint[\"after\"];\n");
    Run result = run("-file", file.toString());
    assertEquals(4, result.status);
    assertTrue(result.out.contains("before"), result.out);
    assertFalse(result.out.contains("after"), result.out);
  }

  @Test
  public void aBareFileArgumentIsTheScript(@TempDir Path directory) throws IOException {
    Path file = script(directory, "args.wls",
        "#!/usr/bin/env symjascript\nPrint[$ScriptCommandLine];\n");
    Run result = run(file.toString(), "2026", "--verbose");
    assertEquals(0, result.status);
    // the script name first, then its own arguments - never the interpreter's options
    assertEquals("{" + file + ",2026,--verbose}", result.out.trim());
  }

  @Test
  public void scriptIsAnotherSpellingOfFile(@TempDir Path directory) throws IOException {
    Path file = script(directory, "name.wls", "Print[First[$ScriptCommandLine]];\n");
    assertEquals(file.toString(), run("-script", file.toString()).out.trim());
  }

  @Test
  public void aProgramCanArriveOnStdin() {
    Run result = runWithStdin("Print[2 + 2];\nExit[5];\n");
    assertEquals(5, result.status);
    assertTrue(result.out.contains("4"), result.out);
  }

  @Test
  public void aScriptSeesTheWolframLanguageVersion(@TempDir Path directory) throws IOException {
    Path file = script(directory, "version.wls", "Print[$VersionNumber];\n");
    // A script gates its features on $VersionNumber, so in a script it names the Wolfram Language
    // version Symja follows...
    assertEquals("14.1", run("-file", file.toString()).out.trim());
    // ...while -code is Symja talking to its own user, and reports Symja's version
    assertFalse(run("-code", "Print[$VersionNumber]").out.trim().equals("14.1"));
  }

  @Test
  public void versionNamesSymjaInEveryMode() {
    // this is how a script tells which kernel it is really talking to
    assertEquals("True", run("-code", "Print[StringStartsQ[$Version, \"Symja\"]]").out.trim());
  }

  @Test
  public void commandLineStartsWithSomethingRunnable() {
    // First[$CommandLine] is how a script starts a second kernel, so it has to be a command
    String first = run("-code", "Print[First[$CommandLine]]").out.trim();
    assertFalse(first.isEmpty());
    assertTrue(new java.io.File(first).isFile(), first);
  }

  @Test
  public void aScriptPrintsNothingByItself(@TempDir Path directory) throws IOException {
    Path file = script(directory, "quiet.wls", "2 + 2\n");
    assertEquals("", run("-file", file.toString()).out.trim());
    // ...unless it is asked for
    assertEquals("4", run("-print", "-file", file.toString()).out.trim());
  }
}
