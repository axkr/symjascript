symjascript (native build)
==========================

A command-line interpreter for the Symja computer algebra system, using a
syntax close to Mathematica's, with a command line modelled on wolframscript.

This is the GraalVM native build: a single self-contained executable. It needs
no Java runtime and starts several times faster than the jar. If you want the
portable, run-anywhere version instead, download the plain symjascript zip,
which runs on any Java 11 or newer.


Running
-------

    bin/symjascript

Unlike the jar distribution this binary depends on nothing beside it - copy
bin/symjascript anywhere on your PATH on its own.

    bin/symjascript -c "Integrate[Sin[x]*Exp[x],x]"
    bin/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG > plot.svg

Run bin/symjascript -h for the full option list.


Ways to run
-----------

    symjascript                                interactive session
    symjascript -code '<code>'                 evaluate code, print the result
    symjascript -f script.wls [args...]        run a script (-script, or just
    symjascript script.wls [args...]           the file name, work too)
    symjascript -function f -args 1 2          evaluate f[1, 2]
    symjascript < program.wls                  run a program from stdin
    symjascript -wstp                          serve a kernel link on stdin/stdout


Scripts, options and arguments
------------------------------

As in wolframscript, symjascript recognises its options wherever they appear,
also after the script file; whatever it does not recognise is an argument of the
script. Put arguments that look like options after --:

    bin/symjascript -f report.wls -print all 2026   # -print all: symjascript
                                                    # 2026: the script
    bin/symjascript -f report.wls -- -print         # -print: the script

A script reads its arguments from $ScriptCommandLine, which holds the script
name followed by its arguments, and finds itself through $InputFileName. A
script prints nothing but what it Prints unless -print (last result) or
-print all (every result) is given; -code always prints its result.

A script file can carry a shebang line, which is ignored when it is read:

    #!/usr/bin/env -S symjascript -f

The -S matters on Linux, which passes everything after the interpreter as a
single argument. An absolute path works everywhere without it:

    #!/usr/local/bin/symjascript -f

-timeout <secs> [value] limits each evaluation - each statement of a script -
and gives value, or $TimedOut, when the limit is reached. -linewise runs the
code once per line of stdin, with the line in $ScriptInputString.

The exit status is 0 unless Exit[n] sets it, as in wolframscript; symjascript
exits with 1 only for command-line problems such as an unknown option or a
missing file.


macOS note
----------

The binary is not notarized. macOS will refuse to run it on first launch;
allow it under System Settings > Privacy & Security, or remove the quarantine
flag yourself:

    xattr -d com.apple.quarantine bin/symjascript


Differences from the jar
------------------------

JAVA_OPTS is not honoured; there is no JVM to configure.

Raster output - -format PNG, JPEG, GIF, BMP, TIFF and PNM - rasterises the
picture through AWT. GraalVM supports AWT in a native image on Linux and
Windows, but not yet on macOS (oracle/graal issue 13272). On a macOS native
build those formats report that and suggest -format SVG, which always works and
is a vector format anyway. Image[...] and the other image functions report
the same and stay unevaluated. Everything else - the full function library,
arbitrary precision, integration, solving, graphs, SVG output - is identical to
the jar.


Start-up files
--------------

Before evaluating anything, symjascript reads, in this order:

    $BaseDirectory/Kernel/init.m
    $UserBaseDirectory/Kernel/init.m
    every Autoload/<app>/init.m and Autoload/<app>/Kernel/init.m below both

$BaseDirectory defaults to "Symja" in your home directory; $UserBaseDirectory is
~/Library/Symja on macOS, %APPDATA%\Symja on Windows, ~/.Symja elsewhere. The
user file is read last, so it can override the installation-wide one. Both can
be moved with SYMJA_BASE_DIRECTORY and SYMJA_USER_BASE_DIRECTORY, and -noinit
skips them.


Security
--------

symjascript runs with the rights of the user who starts it: programs can read
and write files, start processes, open sockets and launch other kernels, and
start-up files are evaluated automatically. Never feed it input you do not
trust.


First steps
-----------

    In[1]:= 2+2
    In[2]:= Integrate[Sin[x]*Exp[x],x]
    In[3]:= Solve[x^2-4==0,x]
    In[4]:= ?Sin                        help for a function
    In[5]:= /exit                       quit

Press TAB to complete function names.


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
