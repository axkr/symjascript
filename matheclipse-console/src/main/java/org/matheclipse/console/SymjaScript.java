package org.matheclipse.console;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.apache.commons.io.output.StringBuilderWriter;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.matheclipse.core.basic.Config;
import org.matheclipse.core.basic.ToggleFeature;
import org.matheclipse.core.convert.AST2Expr;
import org.matheclipse.core.eval.Errors;
import org.matheclipse.core.eval.EvalControlledCallable;
import org.matheclipse.core.eval.EvalEngine;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.eval.exception.AbortException;
import org.matheclipse.core.eval.exception.ExitException;
import org.matheclipse.core.eval.exception.FailedException;
import org.matheclipse.core.eval.exception.ReturnException;
import org.matheclipse.core.eval.exception.Validate;
import org.matheclipse.core.eval.util.InitFileLoader;
import org.matheclipse.core.eval.util.PackageUtil;
import org.matheclipse.core.eval.util.SourceCodeProperties;
import org.matheclipse.core.eval.util.SymjaDirectories;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.S;
import org.matheclipse.core.form.Documentation;
import org.matheclipse.core.form.output.ASCIIPrettyPrinter3;
import org.matheclipse.core.form.output.OutputFormFactory;
import org.matheclipse.core.interfaces.IASTAppendable;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.core.interfaces.ISymbol;
import org.matheclipse.parser.client.ParserConfig;
import org.matheclipse.parser.client.Scanner;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.ast.ASTNode;
import org.matheclipse.parser.client.math.MathException;

/**
 * A read-eval-print loop console for Mathematica like syntax input of expressions.
 */
public class SymjaScript {

  private final static String RESET = "\u001B[0m";
  private final static String GREEN = "\033[0;32m";
  private final static String RED = "\033[0;31m";
  private final static String BLUE = "\033[0;34m";

  private long fSeconds = -1;

  private static final int OUTPUTFORM = 0;
  private static final int JAVAFORM = 1;
  private static final int TRADITIONALFORM = 2;
  private static final int PRETTYFORM = 3;
  private static final int INPUTFORM = 4;

  private int fUsedForm = OUTPUTFORM;

  /**
   * Process exit status. A script needs to be able to tell that
   * <code>symjascript ... -format PNG &gt; plot.png</code> produced nothing, so every diagnostic
   * below sets this and main() exits with it.
   */
  private static int exitStatus = 0;

  private static final String PRINT_LAST = "last";
  private static final String PRINT_ALL = "all";
  private static final String PRINT_NONE = "none";

  /** Charset for output, from <code>-charset</code>. */
  private String fCharset = null;

  /** <code>-linewise</code>: run the code once per line of stdin. */
  private boolean fLinewise = false;

  /** <code>-print</code> / <code>-print all</code>; null means the default for the mode. */
  private String fPrintMode = null;

  /** <code>-verbose</code>. */
  private boolean fVerbose = false;

  /** Optional value returned when <code>-timeout</code> expires. */
  private String fTimeoutValue = null;

  /** <code>-noinit</code>: skip the start-up files in $BaseDirectory / $UserBaseDirectory. */
  private boolean fNoInit = false;

  /** Extra files named with <code>-initfile</code> (and the older <code>-default</code>). */
  private final List<String> fInitFiles = new ArrayList<String>();

  /**
   * Format requested with <code>-format</code>, upper case, or <code>null</code> for ordinary text
   * output. <code>SVG</code> is produced directly; every other value is treated as an ImageIO
   * format name (PNG, JPEG, ...) and rasterized from the SVG.
   */
  private String fOutputFormat = null;

  private ExprEvaluator fEvaluator;
  private OutputFormFactory fOutputFactory;
  private OutputFormFactory fOutputTraditionalFactory;
  private OutputFormFactory fInputFactory;

  private String fDefaultSystemRulesFilename;

  private static int COUNTER = 1;

  private static PrintWriter stdout;
  private static PrintWriter stderr;
  private static Terminal terminal;

  /* package private */ static int runConsole(final String args[], PrintWriter out,
      PrintWriter err) {
    stdout = out;
    stderr = err;
    return run(args);
  }

  public static void main(final String args[]) {
    System.exit(run(args));
  }

  /**
   * Run one invocation and answer the status the process should end with. Split from
   * {@link #main(String[])} so that a test can drive the console without ending the JVM: the only
   * thing main adds is {@link System#exit(int)}.
   */
  private static int run(final String args[]) {
    exitStatus = 0;
    // one run must not decide how the next one reports $VersionNumber
    Config.WOLFRAMSCRIPT_COMPAT = false;
    Locale.setDefault(Locale.US);
    ParserConfig.PARSER_USE_LOWERCASE_SYMBOLS = false;
    ToggleFeature.COMPILE = true;
    ToggleFeature.COMPILE_PRINT = true;
    Config.BUILTIN_PROTECTED = ISymbol.NOATTRIBUTE;
    Config.JAVA_UNSAFE = true;
    Config.SHORTEN_STRING_LENGTH = 1024;
    Config.USE_VISJS = true;
    Config.FILESYSTEM_ENABLED = true;
    // Symja owns this process, so Exit[] and Quit[] may end it with a status
    Config.PROCESS_MODE = true;
    setCommandLine(args);
    F.initSymja();

    // JLine's system terminal writes straight to the terminal device, and it
    // hands out the same writer for both streams. For an interactive session
    // that is what you want. For a one-shot run it is wrong twice over:
    //
    // symja -code 'Plot[Sin[x],{x,0,10}]' -format SVG > plot.svg
    //
    // would print the picture on screen and leave plot.svg empty, because the
    // terminal writer never sees the shell's redirection; and any diagnostic
    // would land on stdout, inside the redirected file. So for a one-shot run
    // use the process's own streams: stdout then follows the redirection and
    // stderr stays separate from it.
    //
    // A one-shot run also builds no terminal at all. JLine warns on stderr when
    // it cannot open a system terminal, and that warning has no business in the
    // output of `symjascript script.wls`.
    boolean interactive = !isNonInteractive(args);
    if (interactive) {
      try {
        terminal = TerminalBuilder.builder()//
            .system(true)//
            .jna(true) // Force JLine to use JNA for Windows native API calls
            .build();
        stdout = terminal.writer();
        stderr = terminal.writer();
      } catch (IOException e) {
        System.err.println("Could not initialize JLine Terminal: " + e.getMessage());
        return 1;
      }
    } else {
      stdout = new PrintWriter(System.out, true);
      stderr = new PrintWriter(System.err, true);
    }

    SymjaScript console;
    try {
      console = new SymjaScript();
      Config.PRINT_OUT = console::printOut;
    } catch (final SyntaxError e1) {
      e1.printStackTrace();
      return 1;
    }

    try {
      console.setArgs(args);
    } catch (ReturnException re) {
      stdout.flush();
      stderr.flush();
      return exitStatus;
    } catch (ExitException ee) {
      // Exit[n] in a script, an init file or -code
      stdout.flush();
      stderr.flush();
      return ee.getExitCode();
    }
    LineReader reader = LineReaderBuilder.builder().terminal(terminal)
        .completer(new SymjaCompleter()).highlighter(new SymjaHighlighter()).build();

    stdout.println("Symja version " + Config.VERSION + " initialized");
    stdout.flush();

    String trimmedInput;

    while (true) {
      try {
        String inputExpression = reader.readLine("\n" + BLUE + ">> " + RESET);
        if (inputExpression == null) {
          continue;
        }

        trimmedInput = inputExpression.trim();

        if (trimmedInput.isEmpty()) {
          continue;
        }

        if (inputExpression.length() > 1
            && inputExpression.charAt(inputExpression.length() - 1) == '\t'
            && Scanner.isIdentifier(trimmedInput)) {
          String docInput = "?" + trimmedInput + "*";
          IExpr doc = Documentation.findDocumentation(docInput);
          stdout.println(doc.toString());
          continue;
        }

        if (trimmedInput.length() >= 4 && trimmedInput.charAt(0) == '/') {
          String command = trimmedInput.substring(1).toLowerCase(Locale.ENGLISH);
          if (command.equals("exit")) {
            stdout.println("Closing Symja console... bye.");
            break;
          } else if (command.equals("java")) {
            stdout.println("Enabling output for JavaForm");
            console.fUsedForm = JAVAFORM;
            continue;
          } else if (command.equals("traditional")) {
            stdout.println("Enabling output for TraditionalForm");
            console.fUsedForm = TRADITIONALFORM;
            continue;
          } else if (command.equals("output")) {
            stdout.println("Enabling output for OutputForm");
            console.fUsedForm = OUTPUTFORM;
            continue;
          } else if (command.equals("pretty")) {
            stdout.println("Enabling output for PrettyPrinterForm");
            console.fUsedForm = PRETTYFORM;
            continue;
          } else if (command.equals("input")) {
            stdout.println("Enabling output for InputForm");
            console.fUsedForm = INPUTFORM;
            continue;
          } else if (command.equals("timeoutoff")) {
            stdout.println("Disabling timeout for evaluation");
            console.fSeconds = -1;
            continue;
          } else if (command.equals("timeouton")) {
            stdout.println("Enabling timeout for evaluation to 60 seconds.");
            console.fSeconds = 60;
            continue;
          }
        }

        String postfix = Scanner.balanceCode(trimmedInput);
        if (postfix != null && postfix.length() > 0) {
          stderr.println("Automatically closing brackets: " + postfix);
          trimmedInput = trimmedInput + postfix;
        }
        stdout.println(GREEN + "In[" + COUNTER + "]:= " + RESET + trimmedInput);
        stdout.flush();

        console.resultPrinter(trimmedInput);
        COUNTER++;

      } catch (UserInterruptException e) {
        // Ignore Ctrl-C
        continue;
      } catch (EndOfFileException e) {
        // Exit on Ctrl-D
        stdout.println("Closing Symja console... bye.");
        break;
      } catch (ExitException ee) {
        // Exit[] / Quit[] typed at the prompt
        stdout.flush();
        stderr.flush();
        return ee.getExitCode();
      } catch (final Exception e) {
        stderr.println(e.getMessage());
        stderr.flush();
      }
    }
    stdout.flush();
    stderr.flush();
    return exitStatus;
  }

  /**
   * Fill in <code>$CommandLine</code> and the command that starts another copy of this interpreter.
   *
   * <p>
   * <code>First[$CommandLine]</code> has to be runnable, because that is how a script starts a
   * second kernel. For a native image that is the binary itself; running from a jar the binary name
   * would be <code>java</code> with no class path, so the launcher scripts pass their own path in
   * <code>symja.executable</code> and the full java command is remembered separately.
   */
  private static void setCommandLine(final String args[]) {
    String executable = System.getProperty("symja.executable");
    List<String> relaunch = new ArrayList<String>();
    if (executable == null || executable.isEmpty()) {
      executable = ProcessHandle.current().info().command().orElse("symjascript");
      String classPath = System.getProperty("java.class.path");
      if (classPath == null || classPath.isEmpty()) {
        // a native image: the binary is the whole command
        relaunch.add(executable);
      } else {
        relaunch.add(executable);
        relaunch.add("-cp");
        relaunch.add(classPath);
        relaunch.add(SymjaScript.class.getName());
      }
    } else {
      relaunch.add(executable);
    }
    Config.RELAUNCH_COMMAND = relaunch;

    IASTAppendable commandLine = F.ListAlloc(args.length + 1);
    commandLine.append(executable);
    for (String arg : args) {
      commandLine.append(arg);
    }
    Config.COMMAND_LINE = commandLine;
  }

  /**
   * Does this command line ask for one-shot work rather than a session? Then the process's own
   * streams are used, so that a shell redirection reaches the output and diagnostics stay out of it.
   */
  private static boolean isNonInteractive(final String args[]) {
    for (String arg : args) {
      if (arg.equals("-code") || arg.equals("-c") //
          || arg.equals("-function") || arg.equals("-fun") //
          || arg.equals("-file") || arg.equals("-f") //
          || arg.equals("-script") //
          || arg.equals("-format")) {
        return true;
      }
    }
    // `symjascript script.wls` is a one-shot run as well, and so is a program on stdin - with
    // stdin on a pipe JLine builds a dumb terminal around System.out anyway, so this only makes
    // the choice explicit
    return scriptFileArgument(args) != null || System.console() == null;
  }

  /**
   * The bare argument naming a script, as in <code>symjascript report.wls 2026</code>, or
   * <code>null</code> when the command line does not start with one. Only the first bare token is
   * considered, and only when it names a readable file, so that a mistyped option is still reported
   * as an unknown option rather than silently ignored.
   */
  private static String scriptFileArgument(final String args[]) {
    for (String arg : args) {
      if (isOption(arg)) {
        // any option decides how the run works; a bare script only counts before one
        return null;
      }
      return new File(arg).isFile() ? arg : null;
    }
    return null;
  }



  private String resultPrinter(String inputExpression) {
    String outputExpression = interpreter(inputExpression);
    if (outputExpression.length() > 0) {
      stdout.print(RED + "Out[" + COUNTER + "]= " + RESET);
      stdout.flush();
      stdout.println(
          Errors.shorten(outputExpression, fEvaluator.getEvalEngine().getOutputSizeLimit()));
      stdout.flush();
    }
    return outputExpression;
  }

  private static void printUsage() {
    final String lineSeparator = System.getProperty("line.separator");
    final StringBuilder msg = new StringBuilder();
    msg.append(Config.SYMJA);
    msg.append(Config.COPYRIGHT);
    msg.append(
        "Symja Console Wiki: https://github.com/axkr/symja_android_library/wiki/Console-apps")
        .append(lineSeparator);
    msg.append(lineSeparator);
    msg.append("org.matheclipse.console.SymjaScript [options]").append(lineSeparator);
    msg.append(lineSeparator);
    msg.append("Program arguments: ").append(lineSeparator);
    msg.append("  -c, -code <code>            evaluate the code").append(lineSeparator);
    msg.append("  <file>                      evaluate a script file, as with -file")
        .append(lineSeparator);
    msg.append("  -f, -file, -script <file>   evaluate a script file; a #! first line is")
        .append(lineSeparator);
    msg.append("                              ignored, and everything after the file is passed")
        .append(lineSeparator);
    msg.append("                              to the script - put options before -f")
        .append(lineSeparator);
    msg.append("  -fun, -function <f>         evaluate a function, with -args")
        .append(lineSeparator);
    msg.append(
        "  -s, -signature <type>...    types for the -args values (String, Integer, Real, ...)")
        .append(lineSeparator);
    msg.append("  -a, -args, -- <value>...    values passed to -function").append(lineSeparator);
    msg.append("  -format <type>              write the result to stdout as SVG, Base64,")
        .append(lineSeparator);
    msg.append("                              ExpressionJSON, Table, or a raster format such")
        .append(lineSeparator);
    msg.append("                              as PNG (raster needs AWT - see the README)")
        .append(lineSeparator);
    msg.append("  -charset <encoding>         encoding for the output").append(lineSeparator);
    msg.append("  -linewise                   run the code once per line of stdin, as $ScriptLine")
        .append(lineSeparator);
    msg.append("  -print [all]                print the last result, or every result with 'all'")
        .append(lineSeparator);
    msg.append("  -timeout <secs> [value]     abort an evaluation after secs, returning value")
        .append(lineSeparator);
    msg.append("  -l, -local [kernelpath]     accepted and ignored - symjascript is always local")
        .append(lineSeparator);
    msg.append("  -initfile <file>            evaluate an extra file at start-up (repeatable)")
        .append(lineSeparator);
    msg.append("  -noinit                     skip Kernel/init.m and the Autoload directories")
        .append(lineSeparator);
    msg.append("  -d, -default <file>         older spelling of -initfile").append(lineSeparator);
    msg.append("  -v, -verbose                report which start-up files were loaded")
        .append(lineSeparator);
    msg.append("  -version                    print the version and exit").append(lineSeparator);
    msg.append("  -h, -help                   print this message").append(lineSeparator);
    msg.append(lineSeparator);
    msg.append("  symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG > plot.svg")
        .append(lineSeparator);
    msg.append(lineSeparator);
    msg.append("To stop the program type: /exit<RETURN>").append(lineSeparator);
    msg.append(
        "To get the available identifiers type: ident<TAB> (and press further TABs to select the completion)")
        .append(lineSeparator);
    msg.append("To disable the evaluation timeout type: /timeoutoff<RETURN>").append(lineSeparator);
    msg.append("To enable the evaluation timeout type: /timeouton<RETURN>").append(lineSeparator);
    msg.append("To enable the output in Java form: /java<RETURN>").append(lineSeparator);
    msg.append("To enable the output in standard form: /output<RETURN>").append(lineSeparator);
    msg.append("To enable the output in traditional form: /traditional<RETURN>")
        .append(lineSeparator);
    msg.append("****+****+****+****+****+****+****+****+****+****+****+****+");

    stdout.println(msg.toString());
    stdout.flush();
  }

  public SymjaScript() {
    EvalEngine engine = new EvalEngine(false);
    EvalEngine.set(engine);
    fEvaluator = new ExprEvaluator(engine, false, (short) 100);
    EvalEngine evalEngine = fEvaluator.getEvalEngine();
    evalEngine.setFileSystemEnabled(true);
    evalEngine.setRecursionLimit(Config.DEFAULT_RECURSION_LIMIT);
    evalEngine.setIterationLimit(Config.DEFAULT_ITERATION_LIMIT);
    evalEngine.setErrorPrintStream(System.err);
    evalEngine.setOutPrintStream(System.out);
    fOutputFactory = OutputFormFactory.get(false, false, 5, 7);
    // Print Graphics/Graphics3D as -Graphics-/-Graphics3D- instead of dumping the
    // whole primitive list.
    // Use -format to get the picture itself.
    fOutputFactory.setGraphicsPlaceholder(true);
    fOutputTraditionalFactory = OutputFormFactory.get(true, false, 5, 7);
    fInputFactory = OutputFormFactory.get(false, false, 5, 7);
    fInputFactory.setInputForm(true);
  }

  private static final String[] UNSUPPORTED_OPTIONS =
      {"-api", "-cloud", "-o", "-wstpserver", "-startprofile", "-continueprofile", "-kernelid",
          "-kernelpool", "-auth", "-authenticate", "-username", "-password", "-permissionskey",
          "-entitlement", "-listwstpservers", "-disconnect"};

  /** Does this token start an option rather than being a value? */
  private static boolean isOption(String arg) {
    return arg.length() > 1 && arg.charAt(0) == '-';
  }

  private void setArgs(final String args[]) {
    String code = null;
    String file = null;
    String function = null;
    List<String> functionArgs = null;
    List<String> signature = new ArrayList<String>();
    // Bare tokens are the arguments the script itself receives, as in
    // ./report.m 2026 --verbose
    // which reaches us as: -f ./report.m 2026 --verbose
    List<String> scriptArgs = new ArrayList<String>();

    for (int i = 0; i < args.length; i++) {
      final String arg = args[i];

      if (arg.equals("-code") || arg.equals("-c")) {
        code = requireValue(args, i, "-code");
        i++;

      } else if (arg.equals("-file") || arg.equals("-f") || arg.equals("-script")) {
        // -f is the file. It used to mean -function here; if the
        // argument does not name a file, say so rather than failing obscurely later.
        file = requireValue(args, i, arg.equals("-script") ? "-script" : "-file");
        i++;
        // Option parsing stops at the script, the way python, perl and node do it:
        // everything after it belongs to the script. A script's own arguments often start
        // with a dash - `./report.m --verbose` arrives here as `-f ./report.m --verbose` -
        // and they must reach the script rather than be read as interpreter options.
        // Put interpreter options before -f: `symjascript -print all -f report.m`.
        for (int j = i + 1; j < args.length; j++) {
          scriptArgs.add(args[j]);
        }
        i = args.length;

      } else if (arg.equals("-function") || arg.equals("-fun")) {
        function = requireValue(args, i, "-function");
        i++;

      } else if (arg.equals("-signature") || arg.equals("-s")) {
        while (i + 1 < args.length && !isOption(args[i + 1])) {
          signature.add(args[++i]);
        }

      } else if (arg.equals("-args") || arg.equals("-a") || arg.equals("--")) {
        // everything after -args is a value, never an option
        functionArgs = new ArrayList<String>();
        for (int j = i + 1; j < args.length; j++) {
          functionArgs.add(args[j]);
        }
        i = args.length;

      } else if (arg.equals("-format")) {
        fOutputFormat = requireValue(args, i, "-format").trim().toUpperCase(Locale.US);
        i++;

      } else if (arg.equals("-charset")) {
        fCharset = requireValue(args, i, "-charset").trim();
        i++;

      } else if (arg.equals("-linewise")) {
        fLinewise = true;

      } else if (arg.equals("-print")) {
        // -print [all]; bare -print means "the last result"
        fPrintMode = PRINT_LAST;
        if (i + 1 < args.length && args[i + 1].equalsIgnoreCase("all")) {
          fPrintMode = PRINT_ALL;
          i++;
        }

      } else if (arg.equals("-timeout")) {
        String seconds = requireValue(args, i, "-timeout");
        i++;
        try {
          fSeconds = Long.parseLong(seconds.trim());
        } catch (NumberFormatException nfe) {
          fail("symjascript: -timeout expects a number of seconds, got " + seconds);
          throw ReturnException.RETURN_FALSE;
        }
        // optional value to return when the timeout is hit
        if (i + 1 < args.length && !isOption(args[i + 1])) {
          fTimeoutValue = args[++i];
        }

      } else if (arg.equals("-noinit")) {
        fNoInit = true;

      } else if (arg.equals("-initfile")) {
        fInitFiles.add(requireValue(args, i, "-initfile"));
        i++;

      } else if (arg.equals("-verbose") || arg.equals("-v")) {
        fVerbose = true;

      } else if (arg.equals("-version")) {
        stdout.println("symjascript " + Config.VERSION);
        stdout.flush();
        throw ReturnException.RETURN_TRUE;

      } else if (arg.equals("-local") || arg.equals("-l")) {
        // Always local - accepted so the command line keeps working.
        // Swallow an optional kernel path.
        if (i + 1 < args.length && !isOption(args[i + 1])) {
          i++;
        }

      } else if (arg.equals("-help") || arg.equals("-h")) {
        printUsage();
        throw ReturnException.RETURN_TRUE;

      } else if (arg.equals("-default") || arg.equals("-d")) {
        // Kept as the older spelling of -initfile. It used to evaluate the file here in the
        // parse loop; it is now queued with the other start-up files so the ordering is the
        // same however it was spelled.
        fDefaultSystemRulesFilename = requireValue(args, i, "-default");
        fInitFiles.add(fDefaultSystemRulesFilename);
        i++;

      } else if (isUnsupportedOption(arg)) {
        fail("symjascript: " + arg //
            + " is a cloud option and has no equivalent here." //
            + " symjascript always evaluates locally.");
        throw ReturnException.RETURN_FALSE;

      } else if (isOption(arg)) {
        fail("symjascript: unknown option " + arg);
        printUsage();
        throw ReturnException.RETURN_FALSE;

      } else if (file == null && code == null && function == null && scriptArgs.isEmpty()
          && new File(arg).isFile()) {
        // `symjascript report.wls 2026 --verbose`, the way wolframscript and a #! line run a
        // script. Everything after it belongs to the script, exactly as with -file.
        file = arg;
        for (int j = i + 1; j < args.length; j++) {
          scriptArgs.add(args[j]);
        }
        i = args.length;

      } else {
        scriptArgs.add(arg);
      }
    }

    // $ScriptCommandLine is the script name followed by its arguments - never the options
    // that started the interpreter, and empty when no script is running.
    Config.setScriptCommandLine(file, scriptArgs);

    dispatch(code, file, function, functionArgs, signature);
  }

  /** Report a failure on stderr and remember that the run failed. */
  private static void fail(String message) {
    exitStatus = 1;
    stderr.println(message);
    stderr.flush();
  }

  private static boolean isUnsupportedOption(String arg) {
    for (String unsupported : UNSUPPORTED_OPTIONS) {
      if (unsupported.equals(arg)) {
        return true;
      }
    }
    return false;
  }

  private String requireValue(String[] args, int i, String option) {
    if (i + 1 >= args.length) {
      fail("symjascript: " + option + " needs an argument");
      throw ReturnException.RETURN_FALSE;
    }
    return args[i + 1];
  }

  /**
   * Run whatever the command line asked for; fall through to the REPL when it asked for nothing.
   */
  private void dispatch(String code, String file, String function, List<String> functionArgs,
      List<String> signature) {
    applyCharset();
    if (fOutputFormat != null) {
      // With -format, stdout carries the exported bytes and nothing else. Print[] from an
      // init.m - or from the expression itself - would otherwise be written into the
      // redirected file ahead of the data and corrupt it.
      fEvaluator.getEvalEngine().setOutPrintStream(System.err);
    }
    runStartupFiles();

    if (code != null) {
      runSource(code, "-code");
      throw ReturnException.RETURN_TRUE;
    }

    if (file != null) {
      // A script is run the way wolframscript runs one, so that a script which gates on
      // $VersionNumber sees the Wolfram Language version Symja follows rather than Symja's own.
      Config.WOLFRAMSCRIPT_COMPAT = true;
      runFile(file);
      throw ReturnException.RETURN_TRUE;
    }

    if (function != null) {
      runFunction(function, functionArgs, signature);
      throw ReturnException.RETURN_TRUE;
    }

    if (fLinewise) {
      fail("symjascript: -linewise needs -code or -file");
      throw ReturnException.RETURN_FALSE;
    }

    if (System.console() == null) {
      // Nothing was asked for and stdin is not a terminal, so the program is on stdin:
      // `symjascript < report.wls` and `... | symjascript`, as in wolframscript.
      Config.WOLFRAMSCRIPT_COMPAT = true;
      runStdin();
      throw ReturnException.RETURN_TRUE;
    }
    // no work requested: continue into the interactive REPL
  }

  /**
   * Evaluate the start-up files before anything the command line asked for, so an init.m can define
   * what -code or the session then uses.
   */
  private void runStartupFiles() {
    EvalEngine engine = fEvaluator.getEvalEngine();
    BiConsumer<Path, Throwable> onError =
        (file, problem) -> fail("symjascript: " + file + ": " + problem.getMessage());
    if (!fNoInit) {
      InitFileLoader.loadStartupFiles(engine, onError);
      if (fVerbose) {
        for (Path loaded : SymjaDirectories.kernelInitFiles()) {
          stderr.println("symjascript: loaded " + loaded);
        }
        for (Path loaded : SymjaDirectories.autoloadInitFiles()) {
          stderr.println("symjascript: autoloaded " + loaded);
        }
        stderr.flush();
      }
    }
    if (!fInitFiles.isEmpty()) {
      List<Path> extra = new ArrayList<Path>(fInitFiles.size());
      for (String name : fInitFiles) {
        Path file = Paths.get(name);
        if (!Files.isRegularFile(file)) {
          fail("symjascript: no such init file: " + name);
          continue;
        }
        extra.add(file);
      }
      InitFileLoader.load(extra, engine, onError);
    }
  }

  private void applyCharset() {
    if (fCharset == null) {
      return;
    }
    try {
      Charset charset = Charset.forName(fCharset);
      stdout = new PrintWriter(new OutputStreamWriter(System.out, charset), true);
      stderr = new PrintWriter(new OutputStreamWriter(System.err, charset), true);
    } catch (RuntimeException rex) {
      fail("symjascript: unknown charset " + fCharset);
      throw ReturnException.RETURN_FALSE;
    }
  }

  /**
   * Evaluate a chunk of Symja source. With <code>-linewise</code> the source is run once per line
   * of stdin, with the line available as <code>$ScriptLine</code>.
   */
  private void runSource(String source, String origin) {
    if (!fLinewise) {
      evaluateAndPrint(source, true);
      return;
    }
    try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in,
        fCharset == null ? Charset.defaultCharset() : Charset.forName(fCharset)))) {
      // $ScriptLine is assigned here. The assignment goes through the
      // same evaluator that runs the code: building the Set() with F.$s() instead
      // resolves the symbol in a different context, and the first line then sees
      // $ScriptLine still unbound.
      String line;
      while ((line = in.readLine()) != null) {
        fEvaluator.eval("$ScriptLine = \"" + escapeForSymja(line) + "\"");
        evaluateAndPrint(source, true);
      }
    } catch (IOException ioe) {
      fail("symjascript: reading stdin for " + origin + " failed: " + ioe.getMessage());
    }
  }

  /** Evaluate the program waiting on stdin, honouring -print. */
  private void runStdin() {
    String source;
    try {
      byte[] bytes = readAllBytes(System.in);
      source = new String(bytes, fCharset == null ? StandardCharsets.UTF_8 : Charset.forName(fCharset));
    } catch (IOException ioe) {
      fail("symjascript: cannot read stdin: " + ioe.getMessage());
      throw ReturnException.RETURN_FALSE;
    }
    if (source.trim().isEmpty()) {
      return;
    }
    evaluateScript(PackageUtil.withoutShebang(source));
  }

  private static byte[] readAllBytes(java.io.InputStream in) throws IOException {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(8192);
    byte[] chunk = new byte[8192];
    int read;
    while ((read = in.read(chunk)) > 0) {
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  /** Evaluate a script file, honouring -print. */
  private void runFile(String filename) {
    File scriptFile = new File(filename);
    if (!scriptFile.isFile()) {
      fail("symjascript: no such file: " + filename + (filename.startsWith("-") ? ""
          : " (note: -f is the script file, use -function" + " for a function name)"));
      throw ReturnException.RETURN_FALSE;
    }
    String source;
    try {
      source = new String(Files.readAllBytes(scriptFile.toPath()),
          fCharset == null ? StandardCharsets.UTF_8 : Charset.forName(fCharset));
    } catch (IOException ioe) {
      fail("symjascript: cannot read " + filename + ": " + ioe.getMessage());
      throw ReturnException.RETURN_FALSE;
    }
    // A leading #! line is a shebang, not Symja source.
    source = PackageUtil.withoutShebang(source);
    if (fLinewise) {
      runSource(source, "-file");
      return;
    }
    // A script finds its own files through $InputFileName - `ParentDirectory[DirectoryName[
    // $InputFileName]] // SetDirectory` is how a Wolfram Language application locates itself - so
    // it has to name the script, absolutely, before a line of it runs.
    EvalEngine engine = fEvaluator.getEvalEngine();
    String inputFileName = engine.get$InputFileName();
    String input = engine.get$Input();
    try {
      engine.set$InputFileName(scriptFile.getAbsolutePath());
      engine.set$Input(filename);
      evaluateScript(source);
    } finally {
      engine.set$InputFileName(inputFileName);
      engine.set$Input(input);
    }
  }

  /**
   * Evaluate every expression in <code>source</code>. Nothing is printed unless -print was given: a
   * script speaks through Print[].
   */
  private void evaluateScript(String source) {
    EvalEngine engine = fEvaluator.getEvalEngine();
    List<ASTNode> nodes;
    try {
      nodes = PackageUtil.parseReader(source, engine);
    } catch (RuntimeException rex) {
      stderr.println(rex.getMessage());
      stderr.flush();
      throw ReturnException.RETURN_FALSE;
    }
    AST2Expr ast2Expr = new AST2Expr(engine.isRelaxedSyntax(), engine);
    IExpr last = F.NIL;
    for (ASTNode node : nodes) {
      IExpr expr = ast2Expr.convert(node);
      last = engine.evaluate(expr);
      if (PRINT_ALL.equals(fPrintMode)) {
        printOneResult(last);
      }
    }
    if (PRINT_LAST.equals(fPrintMode) && last.isPresent()) {
      printOneResult(last);
    }
  }

  /** Evaluate a single input string and print it unless -print says otherwise. */
  private void evaluateAndPrint(String source, boolean printByDefault) {
    String outputExpression = interpreter(source.trim());
    boolean print = printByDefault && !PRINT_NONE.equals(fPrintMode);
    if (print && outputExpression.length() > 0) {
      stdout.print(outputExpression);
      stdout.println();
      stdout.flush();
    }
  }

  /** Quote a raw stdin line so it can be embedded in a Symja string literal. */
  private static String escapeForSymja(String line) {
    return line.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void printOneResult(IExpr result) {
    String text = printResult(result);
    if (text.length() > 0) {
      stdout.println(text);
      stdout.flush();
    }
  }

  /** <code>-function f -args a b</code> evaluates <code>f[a, b]</code>. */
  private void runFunction(String function, List<String> functionArgs, List<String> signature) {
    StringBuilder call = new StringBuilder(1024);
    call.append(function);
    call.append("[");
    if (functionArgs != null) {
      for (int j = 0; j < functionArgs.size(); j++) {
        if (j > 0) {
          call.append(", ");
        }
        call.append(applySignature(functionArgs.get(j), j, signature));
      }
    }
    call.append("]");
    evaluateAndPrint(call.toString(), true);
  }

  /**
   * Apply the type named by <code>-signature</code> to one command-line value. Command-line values
   * are strings; without a signature they are passed through as written, which is what makes
   * <code>-function 'Function[x, x^2]' -args 3</code> work. A signature of <code>String</code>
   * quotes the value instead.
   */
  private String applySignature(String value, int index, List<String> signature) {
    if (index >= signature.size()) {
      return value;
    }
    String type = signature.get(index);
    if (type.equalsIgnoreCase("String")) {
      return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    if (type.equalsIgnoreCase("Integer") || type.equalsIgnoreCase("Number")
        || type.equalsIgnoreCase("Real") || type.equalsIgnoreCase("Expression")) {
      return value;
    }
    fail("symjascript: unknown -signature type " + type + "; passing the value through");
    return value;
  }

  /* package private */ String interpreter(final String trimmedInput) {
    IExpr result;
    final StringBuilderWriter buf = new StringBuilderWriter();
    try {
      if (fSeconds <= 0) {
        result = fEvaluator.eval(trimmedInput);
      } else {
        result = fEvaluator.evaluateWithTimeout(trimmedInput, fSeconds, TimeUnit.SECONDS, true,
            new EvalControlledCallable(fEvaluator.getEvalEngine()));
      }
      if (result != F.NIL) {
        return printResult(result);
      }
    } catch (final ExitException ee) {
      // Exit[] / Quit[] end the process, and no report of a failed evaluation stands in for that
      throw ee;
    } catch (final AbortException re) {
      return printResult(S.$Aborted);
    } catch (final FailedException re) {
      return printResult(S.$Failed);
    } catch (final SyntaxError se) {
      String msg = se.getMessage();
      stderr.println(msg);
      stderr.flush();
      return "";
    } catch (final RuntimeException re) {
      Throwable me = re.getCause();
      if (me instanceof MathException) {
        Validate.printException(buf, me);
      } else {
        Validate.printException(buf, re);
      }
      stderr.println(buf.toString());
      stderr.flush();
      return "";
    } catch (final Exception | OutOfMemoryError | StackOverflowError e) {
      Validate.printException(buf, e);
      stderr.println(buf.toString());
      stderr.flush();
      return "";
    }
    return buf.toString();
  }

  private String printResult(IExpr result) {
    EvalEngine engine = fEvaluator.getEvalEngine();
    EvalEngine.setReset(engine);
    try {
      if (result.equals(S.Null)) {
        return "";
      }
      if (fOutputFormat != null) {
        return formatResult(result);
      }
      switch (fUsedForm) {
        case JAVAFORM:
          return result.internalJavaString(SourceCodeProperties.JAVA_FORM_PROPERTIES, -1, x -> null)
              .toString();
        case TRADITIONALFORM:
          StringBuilder traditionalBuffer = new StringBuilder();
          fOutputTraditionalFactory.reset(false);
          if (fOutputTraditionalFactory.convert(traditionalBuffer, result)) {
            return traditionalBuffer.toString();
          } else {
            return "ERROR-IN-TRADITIONALFORM";
          }
        case PRETTYFORM:
          ASCIIPrettyPrinter3 prettyBuffer = new ASCIIPrettyPrinter3();
          prettyBuffer.convert(result);
          stdout.println();
          String[] outputExpression = prettyBuffer.toStringBuilder();
          ASCIIPrettyPrinter3.prettyPrinter(stdout, outputExpression, "Out[" + COUNTER + "]: ");
          return "";
        case INPUTFORM:
          StringBuilder inputBuffer = new StringBuilder();
          fInputFactory.reset(false);
          if (fInputFactory.convert(inputBuffer, result)) {
            return inputBuffer.toString();
          } else {
            return "ERROR-IN-INPUTFORM";
          }
        default:
          // Results are always rendered as text. Earlier versions handed the
          // expression to F.show(), which wrote an HTML document to a temp file
          // and opened it in the desktop browser - for Graphics, but also for
          // anything else F.show() could render, e.g. DateObject. A console
          // should print to the console.
          return exprToString(result);
      }
    } finally {

    }
  }

  /**
   * Formats that {@code ExportString} produces as text, so they need nothing beyond
   * matheclipse-core and behave identically on the JVM and in a native image.
   *
   * <p>
   * This is the verified set, not everything {@code Extension} names. CSV and TSV route through
   * {@code TableFormatIO}, which matheclipse-dataset supplies and this console does not depend on;
   * JSON, RawJSON, String, Text, WXF, DOT and GraphML have no {@code ExportString} branch at all
   * and return the call unevaluated. Adding any of them means adding the module or the branch first
   * - do not just put the name back in this list.
   */
  private static final String[] TEXT_FORMATS = {"SVG", "BASE64", "EXPRESSIONJSON", "TABLE"};

  /**
   * Raster formats. These rasterise the SVG through AWT, which is unavailable in a native image on
   * macOS - see oracle/graal#13272. The attempt is made anyway and the {@link UnsatisfiedLinkError}
   * is caught, so the same binary works wherever AWT does.
   */
  private static final String[] RASTER_FORMATS =
      {"PNG", "JPEG", "JPG", "GIF", "BMP", "TIFF", "TIF", "WEBP", "PNM", "TGA", "ICO", "PSD"};

  private static boolean contains(String[] formats, String format) {
    for (String candidate : formats) {
      if (candidate.equals(format)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Serialize the result in the format asked for with <code>-format</code>, so the caller can
   * redirect it into a file:
   *
   * <pre>
   * symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG &gt; plot.svg
   * </pre>
   *
   * @return the text to print, or an empty string when bytes were written to stdout directly
   */
  private String formatResult(IExpr result) {
    if (contains(RASTER_FORMATS, fOutputFormat)) {
      return rasterResult(result);
    }
    if (!contains(TEXT_FORMATS, fOutputFormat)) {
      fail("symjascript: unsupported -format " + fOutputFormat + ". Supported: "
          + String.join(", ", TEXT_FORMATS) + " and the raster formats "
          + String.join(", ", RASTER_FORMATS));
      return "";
    }

    EvalEngine engine = fEvaluator.getEvalEngine();
    IExpr exported =
        engine.evaluate(F.ExportString(result, F.stringx(canonicalFormat(fOutputFormat))));
    if (!exported.isString()) {
      fail("symjascript: cannot export this result as " + fOutputFormat);
      return "";
    }
    return exported.toString();
  }

  /**
   * ExportString wants spelling of the format name, not the upper-case form the command line uses.
   */
  private static String canonicalFormat(String format) {
    if (format.equals("EXPRESSIONJSON")) {
      return "ExpressionJSON";
    }
    if (format.equals("RAWJSON")) {
      return "RawJSON";
    }
    if (format.equals("TABLE")) {
      return "Table";
    }
    if (format.equals("STRING")) {
      return "String";
    }
    return format;
  }

  /**
   * Rasterise a graphic and write the image bytes to stdout. Reached reflectively so the console
   * still links and runs where AWT is missing.
   */
  private String rasterResult(IExpr result) {
    EvalEngine engine = fEvaluator.getEvalEngine();
    IExpr svg = engine.evaluate(F.ExportString(result, F.stringx("SVG")));
    if (!svg.isString()) {
      fail("symjascript: -format " + fOutputFormat
          + " expects a Graphics or Graphics3D expression.");
      return "";
    }
    try {
      stdout.flush();
      if (!Rasterizer.write(svg.toString(), fOutputFormat, System.out)) {
        fail("symjascript: no image writer for " + fOutputFormat + "; -format SVG always works.");
        return "";
      }
      System.out.flush();
    } catch (UnsatisfiedLinkError | NoClassDefFoundError awtMissing) {
      // Native images on macOS have no AWT at all (oracle/graal#13272), so the raster
      // formats cannot work there. Say so instead of dying with a link error.
      fail("symjascript: " + fOutputFormat
          + " needs AWT, which this build does not have. Use -format SVG instead.");
    } catch (IOException ioe) {
      fail("symjascript: writing " + fOutputFormat + " failed: " + ioe.getMessage());
    }
    return "";
  }

  private String exprToString(IExpr result) {
    StringBuilder strBuffer = new StringBuilder();
    fOutputFactory.reset(false);
    fOutputFactory.setSignificantFigures(fEvaluator.getEvalEngine().getSignificantFigures() + 1);
    if (fOutputFactory.convert(strBuffer, result)) {
      String resultString = strBuffer.toString();
      if (resultString.indexOf('\n', 1) > 0 && resultString.charAt(0) != '\n') {
        return "\n" + resultString;
      }
      return resultString;
    }
    return "ERROR-IN-OUTPUTFORM";
  }

  private void printOut(IExpr result) {
    String outputExpression = exprToString(result);
    stdout.println("Out[" + COUNTER + "]= " + outputExpression);
    stdout.flush();
  }

  private String getDefaultSystemRulesFilename() {
    return fDefaultSystemRulesFilename;
  }
}
