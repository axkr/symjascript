# Manual testing against the WLJS Notebook

`start-wljs.sh` (macOS/Linux) and `start-wljs.cmd` (Windows) start the
[WLJS Notebook](https://github.com/WLJSTeam/wljs-notebook) with `symjascript` as
both of its kernels, the master kernel that serves the page and the evaluation
kernel it launches with `-wstp`.

    src/test/wljs/start-wljs.sh --build --open

Needs, checked out next to this repository (or pointed to with `SYMJA_DIR` and
`WLJS_NOTEBOOK_DIR`):

* `symja_android_library`, whose core the console is built against;
* `wljs-notebook` on the `symja-backend` branch (fork `axkr/wljs-notebook`),
  which carries the Symja socket adapter `Packages/CSockets/Kernel/Symja.wl`.

`--build` reinstalls symja's parser and core into `~/.m2` and repackages the
console, which is what a change in core needs before the notebook sees it. The
notebook takes about 90 s to start; it is ready when the log says
`Open http://127.0.0.1:20560 in your browser`. Ctrl-C stops both kernels.

The log goes to `target/wljs-notebook.log`. A message from the evaluation
kernel appears there only as its tag (`KernelWarningGet::error`); to see its
text, `Get` the files listed in the `Loading to Evaluation Kernel...` lines in a
plain `symjascript -script` run from the WLJS directory.

In the browser, `await server.ask('<WL>')` evaluates in the master kernel and
`await server.kernel.ask('<WL>')` in the evaluation kernel, which is much faster
than a restart for probing.

This is not a Maven goal on purpose: the notebook is a long-running server that
is stopped by hand, and it needs a second checkout that the build knows nothing
about.
