// Screenshots a page in headless Chrome (WebGL through SwiftShader) once its title is "done":
//   node shot.mjs <url> <out.png> [width] [height]
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [url, out, width = '1200', height = '1200'] = process.argv.slice(2);
const port = 9300 + Math.floor(Math.random() * 500);
const chrome = spawn('google-chrome', ['--headless=new', '--hide-scrollbars', '--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
    `--window-size=${width},${height}`, `--remote-debugging-port=${port}`,
    `--user-data-dir=${mkdtempSync(join(tmpdir(), 'proc-chrome-'))}`, 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let target;
for (let i = 0; i < 50 && !target; i++) {
    await sleep(200);
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === 'page'); } catch {}
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
let nextId = 1;
const pending = new Map();
ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg.result); pending.delete(msg.id); }
    else if (msg.method === 'Runtime.consoleAPICalled')
        console.log('[console]', msg.params.args.map((a) => a.value ?? a.description).join(' '));
});
const send = (method, params = {}) => new Promise((r) => { const id = nextId++; pending.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
await send('Runtime.enable');
await send('Page.enable');
await send('Page.navigate', { url });
let title = '';
for (let i = 0; i < 600 && title !== 'done' && title !== 'error'; i++) {
    await sleep(200);
    title = (await send('Runtime.evaluate', { expression: 'document.title', returnByValue: true })).result.value;
}
if (title === 'error')
    console.log((await send('Runtime.evaluate', { expression: 'document.body.innerText.slice(0, 3000)', returnByValue: true })).result.value);
const { data } = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(out, Buffer.from(data, 'base64'));
chrome.kill();
process.exit(title === 'done' ? 0 : 1);
