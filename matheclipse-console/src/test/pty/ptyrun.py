"""Run a command with a PTY on stdin (so isatty(0) is true, like a real shell)
while stdout is redirected to a file - exactly the user's scenario."""
import os, pty, sys, time, fcntl, termios

outfile = sys.argv[1]
cmd = sys.argv[2:]
master, slave = pty.openpty()
pid = os.fork()
if pid == 0:
    os.setsid()
    try:
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)   # make it the controlling tty
    except Exception:
        pass
    os.dup2(slave, 0)                               # stdin  = pty
    fd = os.open(outfile, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
    os.dup2(fd, 1)                                  # stdout = file
    os.dup2(slave, 2)                               # stderr = pty
    os.close(master); os.close(slave); os.close(fd)
    os.execvp(cmd[0], cmd)
os.close(slave)
deadline = time.time() + 180
term = b""
while time.time() < deadline:
    if os.waitpid(pid, os.WNOHANG)[0] == pid:
        break
    try:
        r = os.read(master, 65536)
        if r: term += r
    except OSError:
        break
    time.sleep(0.05)
os.close(master)
print(f"[harness] terminal received {len(term)} bytes; contains <svg>: {b'<svg' in term}")
