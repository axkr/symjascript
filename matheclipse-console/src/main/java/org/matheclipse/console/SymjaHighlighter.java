package org.matheclipse.console;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.matheclipse.core.convert.AST2Expr;

/**
 * JLine3 Highlighter for Mathematica like syntax. Colorizes built-in functions, symbols, constants,
 * strings, numbers, and brackets.
 */
public class SymjaHighlighter implements Highlighter {

  private final Set<String> functions;
  private final Set<String> symbols;
  private final Set<String> constants;

  // Regex pattern to tokenize the input line into components relevant for syntax highlighting.
  private static final Pattern TOKEN_PATTERN = Pattern.compile("(?<STRING>\"(?:\\\\\"|[^\"])*\")|"
      + "(?<NUMBER>\\b\\d+\\.?\\d*(?:\\*\\^\\d+)?\\b|\\.\\d+(?:\\*\\^\\d+)?)|"
      + "(?<IDENTIFIER>[a-zA-Z\\$][a-zA-Z0-9\\$]*)|" + "(?<BRACKET>[\\(\\[\\{\\}\\]\\)])|"
      + "(?<OTHER>\\s+|.)");

  public SymjaHighlighter() {
    // Load predefined arrays from AST2Expr into HashSets for O(1) lookup speed
    functions = new HashSet<>(Arrays.asList(AST2Expr.FUNCTION_STRINGS));

    symbols = new HashSet<>();
    symbols.addAll(Arrays.asList(AST2Expr.SYMBOL_STRINGS));
    symbols.addAll(Arrays.asList(AST2Expr.DOLLAR_STRINGS));

    constants = new HashSet<>();
    constants.addAll(Arrays.asList(AST2Expr.PHYSICAL_CONSTANTS_STRINGS));
    constants.addAll(Arrays.asList(AST2Expr.UPPERCASE_SYMBOL_STRINGS));
  }

  @Override
  public AttributedString highlight(LineReader reader, String buffer) {
    AttributedStringBuilder builder = new AttributedStringBuilder();
    Matcher matcher = TOKEN_PATTERN.matcher(buffer);

    // Define ANSI styles for different token types
    AttributedStyle functionStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
    AttributedStyle symbolStyle =
        AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold();
    AttributedStyle stringStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    AttributedStyle numberStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    AttributedStyle bracketStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE);
    AttributedStyle defaultStyle = AttributedStyle.DEFAULT;

    while (matcher.find()) {
      if (matcher.group("STRING") != null) {
        builder.append(matcher.group("STRING"), stringStyle);

      } else if (matcher.group("NUMBER") != null) {
        builder.append(matcher.group("NUMBER"), numberStyle);

      } else if (matcher.group("IDENTIFIER") != null) {
        String id = matcher.group("IDENTIFIER");

        // Dispatch color based on which AST2Expr array contains the identifier
        if (functions.contains(id)) {
          builder.append(id, functionStyle);
        } else if (symbols.contains(id) || constants.contains(id)) {
          builder.append(id, symbolStyle);
        } else {
          // Unknown variables or user-defined symbols
          builder.append(id, defaultStyle);
        }

      } else if (matcher.group("BRACKET") != null) {
        builder.append(matcher.group("BRACKET"), bracketStyle);

      } else if (matcher.group("OTHER") != null) {
        builder.append(matcher.group("OTHER"), defaultStyle);
      }
    }

    return builder.toAttributedString();
  }

  @Override
  public void setErrorPattern(Pattern errorPattern) {
    // Can be implemented if you want to dynamically highlight parse errors using JLine's error
    // handling
  }

  @Override
  public void setErrorIndex(int errorIndex) {
    // Can be implemented to highlight the exact cursor position when a SyntaxError is caught
  }
}
