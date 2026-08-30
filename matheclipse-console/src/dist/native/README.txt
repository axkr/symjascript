symjascript (native build)
==========================

A command-line interpreter for the Symja computer algebra system, using a
syntax close to Mathematica's.

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


Scripts and option ordering
---------------------------

Options are read until the script file, and everything after it belongs to the
script - the rule python, perl and node use. So options for symjascript go
before -f:

    bin/symjascript -print all -f report.m       # -print is for symjascript
    bin/symjascript -f report.m 2026 --verbose   # both go to the script

A script reads its arguments from $ScriptCommandLine, which holds the script
name followed by its arguments:

    {report.m, 2026, --verbose}

A script file can carry a shebang line, which is ignored when it is read:

    #!/usr/bin/env -S symjascript -f

The -S matters on Linux, which passes everything after the interpreter as a
single argument. An absolute path works everywhere without it:

    #!/usr/local/bin/symjascript -f


macOS note
----------

The binary is not notarized. macOS will refuse to run it on first launch;
allow it under System Settings > Privacy & Security, or remove the quarantine
flag yourself:

    xattr -d com.apple.quarantine bin/symjascript


Differences from the jar
------------------------

JAVA_OPTS is not honoured; there is no JVM to configure.

Raster output - -format PNG, JPEG, GIF, BMP, TIFF and the rest - rasterises the
picture through AWT. GraalVM supports AWT in a native image on Linux and
Windows, but not yet on macOS (oracle/graal issue 13272). On a macOS native
build those formats report that and suggest -format SVG, which always works and
is a vector format anyway. Everything else - the full function library,
arbitrary precision, integration, solving, SVG output - is identical to the jar.


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

    https://github.com/axkr/symja_android_library/wiki/Console-apps
    https://github.com/axkr/symja_android_library


License
-------

Distributed under the GNU General Public License v3 (LICENSE-GPL.txt). The
matheclipse-parser, matheclipse-core and matheclipse-external modules it builds
on are published under the GNU Lesser General Public License v3
(LICENSE-LGPL.txt).

This program comes with ABSOLUTELY NO WARRANTY.
