symjascript
===========

A command-line interpreter for the Symja computer algebra system, using a
syntax close to Mathematica's, with a command line modelled on wolframscript.

This is the portable distribution: one jar plus launcher scripts, for any
platform with Java. A native build that needs no Java is published separately.


Requirements
------------

Java 11 or newer. Check with:

    java -version

If Java is installed somewhere the PATH does not cover, set JAVA_HOME.


Running
-------

    bin/symjascript           (Linux, macOS)
    bin\symjascript.bat       (Windows)

bin/symja is kept as an alias for the older name.

The bin/ directory has to stay next to lib/ - the launchers find the jar
relative to themselves. You can symlink bin/symjascript onto your PATH; the
script follows the symlink back to the distribution.

To raise the heap for large computations, set JAVA_OPTS:

    JAVA_OPTS=-Xmx4g bin/symjascript


Ways to run
-----------

    symjascript                                interactive session
    symjascript -code '<code>'                 evaluate code, print the result
    symjascript -f script.wls [args...]        run a script (-script, or just
    symjascript script.wls [args...]           the file name, work too)
    symjascript -function f -args 1 2          evaluate f[1, 2]
    symjascript < program.wls                  run a program from stdin
    symjascript -wstp                          serve a kernel link on stdin/stdout


Command-line options
--------------------

  -c, -code <code>            evaluate the code and print its result
  -f, -file, -script <file>   run a script file; a #! first line is ignored
  -fun, -function <f>         evaluate a function, with -args
  -s, -signature <type>...    types of the -args values: String, Integer,
                              Real, Number or Expression
  -a, -args <value>...        values passed to -function
  --                          with -function: same as -args; after a script
                              file: the rest goes to the script unchanged
  -format <type>              write the result to stdout in another format
  -charset <encoding>         encoding for the output and the script
  -linewise                   run the code once per line of stdin
  -print [all]                for a script: print the last result, or every
                              result
  -timeout <secs> [value]     limit each evaluation; see below
  -initfile <file>            evaluate an extra file at start-up (repeatable)
  -noinit                     skip the start-up files described below
  -l, -local [kernelpath]     accepted and ignored - always local
  -d, -default <file>         older spelling of -initfile
  -v, -verbose                report which start-up files were loaded
  -wstp, -mathlink            kernel link mode, only before a script file
  -version                    print the version and exit
  -h, -help                   print the option list

Options that only make sense for Wolfram's cloud (-cloud, -api, -wstpserver,
-auth and friends) are recognised and refused with an explanation, so a script
ported from wolframscript fails clearly.


Examples
--------

    bin/symjascript -c "Integrate[Sin[x]*Exp[x],x]"
    bin/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG > plot.svg
    bin/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format PNG > plot.png
    bin/symjascript -fun 'Function[x, x^2]' -args 7
    bin/symjascript -f report.wls -print all
    bin/symjascript -f report.wls 2026 --verbose
    printf 'ab\ncd\n' | bin/symjascript -code 'StringReverse[$ScriptInputString]' -linewise


Options and script arguments
----------------------------

As in wolframscript, symjascript recognises its options wherever they appear,
also after the script file. Whatever it does not recognise is an argument of the
script:

    symjascript -f report.wls -print all 2026     # -print all is for symjascript
                                                  # 2026 is for the script

To give the script an argument that looks like one of these options, put it
after --:

    symjascript -f report.wls -- -print           # -print is for the script

A script reads its arguments from $ScriptCommandLine, which holds the script
name followed by the arguments, and nothing else:

    $ScriptCommandLine        {report.wls, 2026}
    First[$ScriptCommandLine] report.wls
    Rest[$ScriptCommandLine]  {2026}

Outside a script $ScriptCommandLine is the empty list. A script also finds
itself through $InputFileName (its absolute path) and $Input (its name as
typed), and $VersionNumber reports the Wolfram Language version Symja follows,
so version checks written for wolframscript keep working. $Version still
names Symja.


What gets printed
-----------------

-code and -function print their result. A script prints only what it Prints,
unless -print is given: -print prints the result of the last statement,
-print all the result of every statement. A statement ending in ; has no
result to print.


Timeouts
--------

-timeout <secs> [value] limits each evaluation to secs seconds: the -code or
-function expression, or each top-level statement of a script. When the limit
is reached that evaluation gives value - or $TimedOut if no value was given -
and a script carries on with the next statement. Output printed before the
limit stays printed.

    bin/symjascript -timeout 2 -code 'Pause[5]; 1'        # $TimedOut
    bin/symjascript -timeout 2 0 -code 'Pause[5]; 1'      # 0

The optional value is the next argument unless it starts with a dash, so
after a script file `-timeout 5 2026` takes 2026 as the value.


-linewise
---------

With -linewise the code or script runs once for each line of standard input,
with the line in $ScriptInputString, as in wolframscript. $ScriptLine holds the
same line, for scripts written against earlier versions. Without -linewise
$ScriptInputString is None.


Exit status
-----------

The exit status is 0 unless the program ends with Exit[n] or Quit[n], which
exit with n - the same as wolframscript. Syntax errors and messages are reported
on stderr but do not change the status.

symjascript itself exits with 1 for problems with the command line: an unknown
option, a missing script or init file, an unsupported -format, a result that
cannot be exported. Diagnostics always go to stderr, so a redirected stdout
only ever holds the result.


Shebang scripts
---------------

A script file can carry a shebang line, which is ignored when it is read:

    #!/usr/bin/env -S symjascript -f

The -S is what makes this portable. Linux passes everything after the
interpreter as a single argument, so without it env looks for a program called
"symjascript -f" and fails; macOS is more forgiving. An absolute path works
everywhere and needs no -S, at the cost of hardcoding the location:

    #!/usr/local/bin/symjascript -f

Make the file executable with chmod +x and run it directly.


Output formats
--------------

-format SVG, Base64, ExpressionJSON and Table are produced by the engine itself
and work in every build.

-format PNG, JPEG (JPG), GIF, BMP, TIFF (TIF) and PNM rasterise the picture
first, which needs AWT. That is available here, in the jar. The native build
has it on Linux and Windows but not on macOS, where it reports the fact and
suggests SVG rather than failing obscurely.


Start-up files
--------------

Before evaluating anything, symjascript reads, in this order:

    $BaseDirectory/Kernel/init.m
    $UserBaseDirectory/Kernel/init.m
    every Autoload/<app>/init.m and Autoload/<app>/Kernel/init.m below both

$BaseDirectory holds files for every user of an installation and defaults to
"Symja" in your home directory. $UserBaseDirectory holds your own and is
~/Library/Symja on macOS, %APPDATA%\Symja on Windows, ~/.Symja elsewhere. The
user file is read last, so it can override the installation-wide one. Both can
be moved with the environment variables SYMJA_BASE_DIRECTORY and
SYMJA_USER_BASE_DIRECTORY.

All of these directories, and each autoloaded application, are on $Path, so Get
and Needs find packages in them.

A start-up file that fails is reported and skipped - the rest still run, and the
exit status is 1. Use -noinit to skip them entirely. A kernel started with
-wstp reads no start-up files.


Security
--------

symjascript runs with the rights of the user who starts it: programs can read
and write files, start processes, open sockets and launch other kernels, and
start-up files are evaluated automatically. Never feed it input you do not
trust.


Interactive session
-------------------

Press TAB to complete function names. Console commands:

    /exit           quit
    /java           print results in Java form
    /input          print results in input form
    /output         print results in standard form
    /pretty         print results as 2D ASCII art
    /traditional    print results in traditional form
    /timeoutoff     disable the evaluation timeout
    /timeouton      limit each evaluation to 60 seconds

Type ?Name for help on a function, for example ?Integrate


Documentation
-------------

    https://github.com/axkr/symjascript
    https://github.com/axkr/symja_android_library/wiki/Console-apps


License
-------

Distributed under the GNU General Public License v3 (LICENSE-GPL.txt). The
matheclipse-parser, matheclipse-core and matheclipse-external modules it builds
on are published under the GNU Lesser General Public License v3
(LICENSE-LGPL.txt).

This program comes with ABSOLUTELY NO WARRANTY.
