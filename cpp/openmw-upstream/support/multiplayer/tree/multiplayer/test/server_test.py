#!/usr/bin/env python3
"""Starts the real openmw-mp-server and plays two clients against it:  server_test.py <path to openmw-mp-server> <mod dir>"""
import json, socket, subprocess, sys, time, threading

binary, mod = sys.argv[1], sys.argv[2]
PORT = 25977
failures = 0

def check(cond, what):
    global failures
    if not cond:
        failures += 1
        print('FAIL:', what)

class Client:
    def __init__(self):
        self.sock = socket.create_connection(('127.0.0.1', PORT), timeout=5)
        self.buf = b''
        self.inbox = []
    def send(self, **msg):
        self.sock.sendall((json.dumps(msg) + '\n').encode())
    def pump(self, seconds=0.5):
        end = time.time() + seconds
        self.sock.settimeout(0.05)
        while time.time() < end:
            try:
                data = self.sock.recv(65536)
                if not data:
                    break
                self.buf += data
            except socket.timeout:
                pass
            while b'\n' in self.buf:
                line, self.buf = self.buf.split(b'\n', 1)
                msg = json.loads(line)
                self.inbox.append(msg)
                if msg.get('t') == 'ping':
                    self.send(t='pong', n=msg['n'])
    def last(self, t):
        for m in reversed(self.inbox):
            if m.get('t') == t:
                return m
    def count(self, t):
        return sum(1 for m in self.inbox if m.get('t') == t)

server = subprocess.Popen([binary, '--cli-only', '--mod', mod, '--port', str(PORT), '--password', 'pw', '--admin-password', 'adm',
                           '--weather', 'rain', '--start-hour', '14', '--motd', 'welcome'],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
output = []
threading.Thread(target=lambda: [output.append(l) for l in server.stdout], daemon=True).start()
def console(line, wait=0.4):
    n = len(output)
    server.stdin.write(line + '\n'); server.stdin.flush()
    time.sleep(wait)
    return ''.join(output[n:])

try:
    time.sleep(1.0)
    a = Client(); a.pump(0.3)
    hello = a.last('hello')
    check(hello and hello['proto'] == 2 and hello['needPassword'] and not hello['host'], 'hello: version, password needed, nobody is host')
    a.send(t='join', proto=2, name='Alice', password='wrong'); a.pump(0.8)
    check(a.last('join_result') and a.last('join_result')['reason'] == 'password', 'wrong password refused')

    a = Client(); a.pump(0.3)
    a.send(t='join', proto=2, name='Alice', password='pw'); a.pump(0.5)
    jr = a.last('join_result')
    check(jr and jr['ok'] and jr['token'] and jr['motd'] == 'welcome', 'joined with the password, got a token and the motd')
    world = a.last('world')
    check(world and world['weather'] == 'rain' and abs(world['time'] - 14 * 3600) < 600, 'the server sets weather and time of day')
    check('date' not in (world or {}) and 'day' not in (world or {}), 'no date is synced')

    b = Client(); b.pump(0.3)
    b.send(t='join', proto=2, name='Bob', password='pw'); b.pump(0.5)
    a.send(t='chat', scope='global', text='hello bob'); b.pump(0.5)
    check(b.last('chat') and b.last('chat')['text'] == 'hello bob', 'chat between two players')
    a.send(t='state', cell='Balmora', pos=[100, 200, 300], yaw=0); a.pump(0.2)
    b.pump(0.5)
    check(b.last('state') and b.last('state')['pos'] == [100, 200, 300], 'positions are relayed')

    # other worlds' actors reach players in the same cell outside the party; loot only reaches the party
    b.send(t='state', cell='Balmora', pos=[1, 2, 3], yaw=0); b.pump(0.2)
    a.send(t='pubactors', cell='Balmora', list=[{'n': 'Morrowind.esm:5', 'r': 'ordinator', 'h': 1, 'p': [1, 2, 3], 'y': 0}])
    a.send(t='loot', cid='Morrowind.esm:77', items={'gold_001': 40})
    b.pump(0.6)
    check(b.last('pubactors') and b.last('pubactors')['id'] == a.last('hello')['id'], 'players outside the party in the same cell get the public actor roster')
    check(not b.last('loot'), 'loot does not go to players outside the party')

    a.pump(2.5)
    players = a.last('players')
    check(players and len(players['list']) == 2 and all('ip' not in p for p in players['list']), 'the player list has no addresses')

    out = console('players')
    check('Alice' in out and '127.0.0.1' in out and 'ms' in out and 'Balmora' in out and 'at 100, 200, 300' in out,
          'console lists name, latency, address and location: ' + out.strip())
    check('Bob' in out and 'Balmora  at 1, 2, 3' in out, 'every player shows their last position: ' + out.strip())
    check('? ms' not in out, 'latency is measured: ' + out.strip())

    a.send(t='admin', cmd='kick', name='Bob'); a.pump(0.4)
    check('Only admins' in (a.last('notice') or {}).get('text', ''), 'a normal player cannot kick')
    a.send(t='admin', cmd='login', password='nope'); a.pump(0.3)
    a.send(t='admin', cmd='login', password='adm'); a.pump(0.3)
    check('now an admin' in (a.last('notice') or {}).get('text', ''), 'admin login works')
    a.send(t='admin', cmd='who'); a.pump(0.4)
    check('127.0.0.1' in (a.last('notice') or {}).get('text', ''), 'an admin can list addresses')
    a.send(t='admin', cmd='weather', text='clear'); a.pump(0.4); b.pump(0.3)
    check(b.last('world') and b.last('world')['weather'] == 'clear', 'an admin changes the weather for everybody')
    a.send(t='admin', cmd='time', text='23.5'); a.pump(0.4)
    check(abs(a.last('world')['time'] - 23.5 * 3600) < 120, 'an admin changes the time')
    console('weather rain')

    # reconnect with the token
    token = jr['token']
    a.sock.close()
    b.pump(0.6)
    check(b.count('away') == 1 and b.count('leave') == 0, 'others see that Alice is away, not gone')
    a2 = Client(); a2.pump(0.3)
    a2.send(t='join', proto=2, name='Alice', password='pw', token=token); a2.pump(0.6)
    r = a2.last('join_result')
    check(r and r['ok'] and r.get('resumed') and r['id'] == jr.get('id', r['id']), 'the token resumes the session')
    b.pump(0.4)
    check(b.count('back') == 1, 'others see that she is back')

    # flooding and bad input
    c = Client(); c.pump(0.3)
    c.sock.sendall(b'not json\n{"t":\n[1,2,3]\n\xff\xfe\n')
    c.pump(0.3)
    d = Client(); d.pump(0.2)
    d.send(t='join', proto=2, name='Dave', password='pw'); d.pump(0.5)
    check(d.last('join_result') and d.last('join_result')['ok'], 'garbage from another client does not hurt the server')

    out = console('help')
    commands = ['players', 'kick', 'ban', 'unban', 'mute', 'unmute', 'announce', 'weather', 'time', 'help', 'quit', '/login', '/who']
    check(all(c in out for c in commands), 'console help lists every command: ' + out.strip()[:80])
    helped = subprocess.run([binary, '--help'], capture_output=True, text=True).stdout
    check(all(c in helped for c in commands) and '--port' in helped and '--admin-password' in helped,
          '--help lists the options and the same commands')
    out = console('weather')
    check('rain' in out, 'console shows the weather: ' + out.strip())
    out = console('announce hello everybody'); a2.pump(0.5)
    check('[Server] hello everybody' in (a2.last('notice') or {}).get('text', ''), 'console announce reaches players')
    log = ''.join(output)
    for expected in ['connection ', 'refused Alice from 127.0.0.1: password', 'Alice (#', 'joined from 127.0.0.1 (1/64 players)',
                     'Bob (#', '[global] Alice: hello bob', 'admin: ', 'wrong password from', 'logged in', 'ran weather clear',
                     'lost connection; their place is kept', 'is back, now from 127.0.0.1', 'weather']:
        check(expected in log, 'the server log shows: ' + expected)
    check(all(line.startswith('[') or line.startswith('  ') or not line.strip() for line in output if 'joined' in line),
          'log lines carry a time')
    check(server.poll() is None, 'the server is still running')
finally:
    server.stdin.write('quit\n'); server.stdin.flush()
    try:
        server.wait(timeout=5)
    except subprocess.TimeoutExpired:
        server.kill(); failures += 1; print('FAIL: the server did not stop on quit')


# ---------------------------------------------------------------- the window (Qt offscreen), driven like a person would
import os
def gui_test():
    global failures
    port = PORT + 1
    shot = os.path.join(os.environ.get('TMPDIR', '/tmp'), 'mp-server-window.png')
    if os.path.exists(shot):
        os.remove(shot)
    env = dict(os.environ, QT_QPA_PLATFORM='offscreen', MP_SERVER_SCREENSHOT=shot,
               MP_SERVER_TEST_STEPS='select Sleepy;kick;select Nerevarine;mute;select Bandit;ban;unban bandit;weather clear;'
                                    'time 23:30;announce Server restarting;command players')
    proc = subprocess.Popen([binary, '--mod', mod, '--port', str(port), '--password', 'pw', '--admin-password', 'adm',
                             '--weather', 'rain'], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []
    threading.Thread(target=lambda: [lines.append(l) for l in proc.stdout], daemon=True).start()
    time.sleep(1.2)
    if any('no window' in l for l in lines):
        proc.kill()
        print('(this build has no window: window tests skipped)')
        return
    names = ['Arin', 'Nerevarine', 'Sleepy', 'Bandit']
    clients = {}
    for name in names:
        c = Client.__new__(Client)
        c.sock = socket.create_connection(('127.0.0.1', port), timeout=5)
        c.buf, c.inbox = b'', []
        c.pump(0.2)
        c.send(t='join', proto=2, name=name, password='pw'); c.pump(0.3)
        c.send(t='state', cell='Balmora', pos=[1, 2, 3], yaw=0)
        clients[name] = c
    clients['Arin'].send(t='admin', cmd='login', password='adm')
    end = time.time() + 9
    while time.time() < end and proc.poll() is None:
        for c in clients.values():
            c.pump(0.1)
    check(proc.poll() is not None, 'the window closed itself after the steps (screenshot written)')
    if proc.poll() is None:
        proc.kill()
    check(clients['Sleepy'].last('kicked') and clients['Sleepy'].last('kicked')['reason'] == 'kicked', 'the Kick button kicks the selected player')
    check(any('muted you' in m.get('text', '') for m in clients['Nerevarine'].inbox if m.get('t') == 'notice'), 'the Mute button mutes the selected player')
    check(clients['Bandit'].last('kicked') and clients['Bandit'].last('kicked')['reason'] == 'banned', 'the Ban button bans the selected player')
    world = clients['Arin'].last('world')
    check(world and world['weather'] == 'clear', 'the weather box sets the weather for everybody')
    check(world and abs(world['time'] - 23.5 * 3600) < 600, 'the time box sets the time of day')
    notices = [m.get('text', '') for m in clients['Arin'].inbox if m.get('t') == 'notice']
    check('[Server] Server restarting' in notices, 'the announcement box reaches the players')
    out = ''.join(lines)
    check('console: kick Sleepy' in out and 'console: ban Bandit' in out and 'console: unban bandit' in out,
          'button presses are logged like console commands')
    check(os.path.exists(shot) and os.path.getsize(shot) > 5000, 'a picture of the window was saved: ' + shot)

gui_test()
print('server tests: all passed' if failures == 0 else '%d failed' % failures)
sys.exit(1 if failures else 0)
