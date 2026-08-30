package org.matheclipse.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.matheclipse.core.convert.AST2Expr;

public class SymjaCompleter implements Completer {
  // Cache the candidates during initialization
  private final List<Candidate> candidates;

  public SymjaCompleter() {
    // Extract all built-in function names directly from the system map
    candidates = new ArrayList<Candidate>();
    for (Map.Entry<String, String> entry : AST2Expr.PREDEFINED_SYMBOLS_MAP.entrySet()) {
      String val = entry.getValue();
      // The single-argument Candidate constructor marks the candidate as a
      // complete word, which makes JLine append a separator after it - so
      // completing "Int" to "Integrate" produced "Integrate [" once the
      // argument list was typed. A Symja function name is followed by "[",
      // never by a space, so the candidate is deliberately not "complete".
      candidates.add(new Candidate(val, val, null, null, null, null, false));
    }
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    String word = line.word();
    for (Candidate match : this.candidates) {
      if (match.value().startsWith(word)) {
        candidates.add(match);
      }
    }
  }
}