"""Drive an interactive console through a PTY: wait for the prompt, type keys,
and report what the terminal echoed back."""
import os, pty, sys, time, fcntl, termios, re

keys = sys.argv[1]          # e.g. "Integrat\t"
cmd  = sys.argv[2:]
keys = keys.encode().decode('unicode_escape')

master, slave = pty.openpty()
pid = os.fork()
if pid == 0:
    os.setsid()
    try: fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
    except Exception: pass
    os.dup2(slave,0); os.dup2(slave,1); os.dup2(slave,2)
    os.close(master); os.close(slave)
    os.execvp(cmd[0], cmd)
os.close(slave)
fcntl.fcntl(master, fcntl.F_SETFL, os.O_NONBLOCK)

buf = b""
def pump(seconds):
    global buf
    end = time.time() + seconds
    while time.time() < end:
        try:
            r = os.read(master, 65536)
            if r: buf += r
        except (OSError, BlockingIOError):
            pass
        time.sleep(0.05)

pump(25)                                    # wait for banner + prompt
mark = len(buf)
os.write(master, keys.encode())             # type the keys
pump(6)
after = buf[mark:]
os.write(master, b"\r/exit\r")
pump(3)
try: os.kill(pid, 9)
except Exception: pass

def strip(b):
    b = re.sub(rb'\x1b\[[0-9;?]*[a-zA-Z]', b'', b)
    b = re.sub(rb'\x1b\][^\x07]*\x07', b'', b)
    return b.replace(b'\x1b', b'')
full = strip(buf).decode('utf8','replace')
# collapse backspace sequences the way a terminal would
out=[]
for ch in full:
    if ch=='\x08':
        if out: out.pop()
    else: out.append(ch)
rendered=''.join(out)
line=[l for l in rendered.splitlines() if 'In[1]' in l]
print("--- rendered last prompt line ---")
print(repr(line[-1] if line else rendered[-300:]))
