#!/usr/bin/env python3
"""A server takes 128 players and tells the rest it is full:  capacity_test.py <path to openmw-mp-server> <mod dir>"""
import asyncio, json, subprocess, sys, time

binary, mod = sys.argv[1], sys.argv[2]
failures = 0

def check(cond, what):
    global failures
    if not cond:
        failures += 1
        print('FAIL:', what)

async def player(port, i, result, hold):
    try:
        r, w = await asyncio.open_connection('127.0.0.1', port)
    except Exception:
        result['refused'] += 1
        return
    w.write((json.dumps({'t': 'join', 'proto': 3, 'name': f'P{i}'}) + '\n').encode())
    verdict, end = None, time.time() + 8
    while time.time() < end and verdict is None:
        try:
            line = await asyncio.wait_for(r.readline(), 1.0)
        except asyncio.TimeoutError:
            continue
        if not line:
            verdict = 'dropped'
            break
        m = json.loads(line)
        if m.get('t') == 'join_result':
            verdict = 'joined' if m.get('ok') else ('full' if m.get('reason') == 'full' else 'other')
        elif m.get('t') == 'ping':
            w.write((json.dumps({'t': 'pong', 'n': m['n']}) + '\n').encode())
    result[verdict or 'dropped'] += 1
    # stay connected (answering pings) while the others are still joining
    end = time.time() + hold
    while time.time() < end:
        try:
            line = await asyncio.wait_for(r.readline(), 0.5)
        except asyncio.TimeoutError:
            continue
        if not line:
            break
        m = json.loads(line)
        if m.get('t') == 'ping':
            w.write((json.dumps({'t': 'pong', 'n': m['n']}) + '\n').encode())
    w.close()

def run(port, max_players, attempts):
    proc = subprocess.Popen([binary, '--cli-only', '--mod', mod, '--port', str(port), '--max-players', str(max_players)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.2)
    result = {'joined': 0, 'full': 0, 'dropped': 0, 'other': 0, 'refused': 0}
    async def main():
        await asyncio.gather(*[player(port, i, result, 4) for i in range(attempts)])
    try:
        asyncio.run(main())
    finally:
        proc.kill()
    return result

r = run(26301, 500, 135)  # asked for 500: a server still stops at 128
check(r['joined'] == 128, 'a server takes 128 players (--max-players 500 is capped): %s' % r)
check(r['full'] == 7 and r['dropped'] == 0, 'the rest are told the server is full, not just cut off: %s' % r)
r = run(26302, 40, 45)
check(r['joined'] == 40 and r['full'] == 5, 'a lower limit is kept: %s' % r)

print('capacity tests: all passed' if failures == 0 else '%d failed' % failures)
sys.exit(1 if failures else 0)
