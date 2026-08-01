package org.matheclipse.console;

import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.output.StringBuilderWriter;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.matheclipse.core.basic.Config;
import org.matheclipse.core.basic.ToggleFeature;
import org.matheclipse.core.eval.Errors;
import org.matheclipse.core.eval.EvalControlledCallable;
import org.matheclipse.core.eval.EvalEngine;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.eval.exception.AbortException;
import org.matheclipse.core.eval.exception.FailedException;
import org.matheclipse.core.eval.exception.ReturnException;
import org.matheclipse.core.eval.exception.Validate;
import org.matheclipse.core.eval.util.SourceCodeProperties;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.S;
import org.matheclipse.core.form.Documentation;
import org.matheclipse.core.form.output.ASCIIPrettyPrinter3;
import org.matheclipse.core.form.output.OutputFormFactory;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.core.interfaces.ISymbol;
import org.matheclipse.parser.client.ParserConfig;
import org.matheclipse.parser.client.Scanner;
import org.matheclipse.parser.client.SyntaxError;
import org.matheclipse.parser.client.math.MathException;

/**
 * A read-eval-print loop console for Wolfram language like syntax input of expressions.
 */
public class MMAConsole {

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

  private ExprEvaluator fEvaluator;
  private OutputFormFactory fOutputFactory;
  private OutputFormFactory fOutputTraditionalFactory;
  private OutputFormFactory fInputFactory;

  private String fDefaultSystemRulesFilename;

  private static int COUNTER = 1;

  private static PrintWriter stdout;
  private static PrintWriter stderr;
  private static Terminal terminal;

  /* package private */ static void runConsole(final String args[], PrintWriter out,
      PrintWriter err) {
    stdout = out;
    stderr = err;
    main(args);
  }

  public static void main(final String args[]) {
    Locale.setDefault(Locale.US);
    ParserConfig.PARSER_USE_LOWERCASE_SYMBOLS = false;
    ToggleFeature.COMPILE = true;
    ToggleFeature.COMPILE_PRINT = true;
    Config.BUILTIN_PROTECTED = ISymbol.NOATTRIBUTE;
    Config.JAVA_UNSAFE = true;
    Config.SHORTEN_STRING_LENGTH = 1024;
    Config.USE_VISJS = true;
    Config.FILESYSTEM_ENABLED = true;
    F.initSymja();

    try {
      terminal = TerminalBuilder.builder()//
          .system(true)//
          .jna(true) // Force JLine to use JNA for Windows native API calls
          .build();
      stdout = terminal.writer();
      stderr = terminal.writer();
    } catch (IOException e) {
      System.err.println("Could not initialize JLine Terminal: " + e.getMessage());
      return;
    }

    LineReader reader = LineReaderBuilder.builder().terminal(terminal)
        .completer(new SymjaCompleter()).highlighter(new SymjaHighlighter()).build();

    MMAConsole console;
    try {
      console = new MMAConsole();
      Config.PRINT_OUT = console::printOut;
    } catch (final SyntaxError e1) {
      e1.printStackTrace();
      return;
    }

    try {
      console.setArgs(args);
    } catch (ReturnException re) {
      return;
    }
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
      } catch (final Exception e) {
        stderr.println(e.getMessage());
        stderr.flush();
      }
    }
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
    msg.append("org.matheclipse.console.MMAConsole [options]").append(lineSeparator);
    msg.append(lineSeparator);
    msg.append("Program arguments: ").append(lineSeparator);
    msg.append("  -h or -help                                 print usage messages")
        .append(lineSeparator);
    msg.append("  -c or -code <command>                       run the command")
        .append(lineSeparator);
    msg.append("  -f or -function <function> -args arg1 arg2  run the function")
        .append(lineSeparator);
    msg.append(
        "  -d or -default <filename>                   use given textfile for an initial package script")
        .append(lineSeparator);
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

  public MMAConsole() {
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
    fOutputTraditionalFactory = OutputFormFactory.get(true, false, 5, 7);
    fInputFactory = OutputFormFactory.get(false, false, 5, 7);
    fInputFactory.setInputForm(true);
  }

  private void setArgs(final String args[]) {
    Config.setScriptCommandLine(args);

    String function = null;
    for (int i = 0; i < args.length; i++) {
      final String arg = args[i];

      if (arg.equals("-code") || arg.equals("-c")) {
        if (i + 1 >= args.length) {
          final String msg = "You must specify an additional command when using the -code argument";
          stdout.println(msg);
          stdout.flush();
          throw ReturnException.RETURN_FALSE;
        }

        String outputExpression = interpreter(args[i + 1].trim());
        if (outputExpression.length() > 0) {
          stdout.print(outputExpression);
          stdout.flush();
        }
        throw ReturnException.RETURN_TRUE;

      } else if (arg.equals("-function") || arg.equals("-f")) {
        if (i + 1 >= args.length) {
          final String msg = "You must specify a function when using the -function argument";
          stdout.println(msg);
          stdout.flush();
          throw ReturnException.RETURN_FALSE;
        }

        function = args[i + 1];
        i++;
      } else if (arg.equals("-args") || arg.equals("-a")) {
        try {
          if (function != null) {
            StringBuilder inputExpression = new StringBuilder(1024);
            inputExpression.append(function);
            inputExpression.append("[");
            for (int j = i + 1; j < args.length; j++) {
              if (j != i + 1) {
                inputExpression.append(", ");
              }
              inputExpression.append(args[j]);
            }
            inputExpression.append("]");
            String outputExpression = interpreter(inputExpression.toString());
            if (outputExpression.length() > 0) {
              stdout.print(outputExpression);
              stdout.flush();
            }
            throw ReturnException.RETURN_TRUE;
          }
          return;
        } catch (final ArrayIndexOutOfBoundsException aioobe) {
          final String msg = "You must specify a function when using the -function argument";
          stdout.println(msg);
          stdout.flush();
          throw ReturnException.RETURN_FALSE;
        }
      } else if (arg.equals("-help") || arg.equals("-h")) {
        printUsage();
        return;
      } else if (arg.equals("-default") || arg.equals("-d")) {
        if (i + 1 >= args.length) {
          final String msg = "You must specify a file when using the -d argument";
          stdout.println(msg);
          stdout.flush();
          throw ReturnException.RETURN_FALSE;
        }

        fDefaultSystemRulesFilename = args[i + 1];
        fEvaluator.eval(F.Get(args[i + 1]));
        i++;

      } else if (arg.charAt(0) == '-') {
        final String msg = "Unknown arg: " + arg;
        stdout.println(msg);
        printUsage();
        return;
      }
    }
    printUsage();
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
          if (Desktop.isDesktopSupported()) {
            IExpr outExpr = result;
            if (result.isAST(S.Graphics) || result.isAST(F.Graphics3D)) {
              outExpr = F.Show(outExpr);
            }
            String html = F.show(outExpr);
            if (html != null && html.length() > 0) {
              return html;
            }
          }
          return exprToString(result);
      }
    } finally {

    }
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