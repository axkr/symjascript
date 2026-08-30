symjascript
===========

A command-line interpreter for the Symja computer algebra system, using a
syntax close to Mathematica's.


Requirements
------------

Java 11 or newer. Check with:

    java -version

If Java is installed somewhere the PATH does not cover, set JAVA_HOME.
 


Running
-------

    bin/symjascript           (Linux, macOS)
    bin\symjascript.bat       (Windows)

`bin/symja` is kept as an alias for the older name.

The bin/ directory has to stay next to lib/ - the launchers locate the jar
relative to themselves. You can symlink bin/symjascript onto your PATH; the
script follows the symlink back to the distribution.

To raise the heap for large computations, set JAVA_OPTS:

    JAVA_OPTS=-Xmx4g bin/symjascript


Command-line options
--------------------

  -c, -code <code>            evaluate the code
  -f, -file <file>            evaluate a script file; everything after it is
                              passed to the script, not read as an option
  -fun, -function <f>         evaluate a function, with -args
  -s, -signature <type>...    types for the -args values
  -args, -- <value>...        values passed to -function
  -format <type>              write the result to stdout in another format
  -charset <encoding>         encoding for the output
  -linewise                   run the code once per line of stdin
  -print [all]                print the last result, or every result
  -timeout <secs> [value]     abort an evaluation after secs
  -initfile <file>            evaluate an extra file at start-up (repeatable)
  -noinit                     skip the start-up files described below
  -l, -local [kernelpath]     accepted and ignored - always local
  -d, -default <file>         older spelling of -initfile
  -v, -verbose                report which start-up files were loaded
  -version                    print the version and exit
  -h, -help                   print the option list

Options that only make sense for cloud infrastructure
(-cloud, -api, -wstpserver, -auth and friends) are recognised and refused with
an explanation, so a ported script should fail clearly.

Anything that fails - an unreadable file, an unsupported format, a syntax
error - is reported on stderr and exits with status 1, so stdout only ever
carries the result and a shell redirection is safe to trust.


Examples
--------

    bin/symjascript -c "Integrate[Sin[x]*Exp[x],x]"
    bin/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format SVG > plot.svg
    bin/symjascript -code 'Plot[Sin[x],{x,0,10}]' -format PNG > plot.png
    bin/symjascript -fun 'Function[x, x^2]' -args 7
    bin/symjascript -print all -f script.m
    bin/symjascript -f report.m 2026 --verbose
    printf '3\n4\n5\n' | bin/symjascript -code 'ToExpression[$ScriptLine]^2' -linewise


Option ordering
---------------

Options are read until the script file, and everything after it belongs to the
script - the same rule python, perl and node use. So options for symjascript go
before -f:

    bin/symjascript -print all -f report.m       # -print is for symjascript
    bin/symjascript -f report.m -print all       # -print is for the script

This is what lets a script take arguments of its own, including the ones that
start with a dash:

    ./report.m 2026 --verbose

A script reads them from $ScriptCommandLine, which holds the script name
followed by its arguments, and nothing else:

    $ScriptCommandLine        {report.m, 2026, --verbose}
    First[$ScriptCommandLine] report.m
    Rest[$ScriptCommandLine]  {2026, --verbose}

Outside a script $ScriptCommandLine is the empty list.


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

-format PNG, JPEG, GIF, BMP, TIFF, WEBP, PNM, TGA, ICO and PSD rasterise the
picture first, which needs AWT. That is available here, in the jar. The native
build has it on Linux and Windows but not on macOS, where it reports the fact
and suggests SVG rather than failing obscurely.


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
exit status is 1. Use -noinit to skip them entirely.


Interactive session
-------------------

Press TAB to complete function names. Console commands:

    /exit           quit
    /java           print results in Java form
    /output         print results in standard form
    /traditional    print results in traditional form
    /timeoutoff     disable the evaluation timeout
    /timeouton      enable the evaluation timeout

Type ?Name for help on a function, for example ?Integrate


Documentation
-------------

    https://github.com/axkr/symja_android_library/wiki/Console-apps
    https://github.com/axkr/symja_android_library


License
-------

Distributed under the GNU General Public License v3 (LICENSE-GPL.txt). The
matheclipse-parser, matheclipse-core and matheclipse-external modules it builds
on are published under the GNU Lesser General Public License v3
(LICENSE-LGPL.txt).

This program comes with ABSOLUTELY NO WARRANTY.
