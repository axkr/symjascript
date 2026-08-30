# PTY test harnesses

Two behaviours of the console only exist when stdin is a terminal, and neither
can be tested by piping input:

* **Output routing.** With a terminal on stdin, JLine builds a *system* terminal
  whose writer goes to the terminal device, bypassing shell redirection. With a
  pipe it falls back to a dumb terminal that wraps `System.out`, and redirection
  appears to work. A bug where `symjascript -code ... -format SVG > plot.svg`
  wrote an empty file was invisible to every piped test.
* **Tab completion.** It does not run at all without a terminal.

## ptyrun.py

Runs a command with a PTY on stdin and stdout redirected to a file - the exact
shape of `symjascript -code '...' -format SVG > out.svg`. Reports how many bytes
reached the file and how many leaked to the terminal.

    python3 ptyrun.py /tmp/out.svg ../../../target/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG

Expect the file to hold the SVG and the terminal to receive nothing.

## ptytype.py

Drives the interactive console: waits for the prompt, types keys, and prints the
line the terminal rendered (escape sequences stripped, backspaces applied).

    python3 ptytype.py 'Integrat\t[' ../../../target/symjascript

Expect `In[1]:= Integrate[]` - a trailing space after the completion means the
completer is handing JLine "complete" candidates again.
