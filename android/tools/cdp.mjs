// 用法：node cdp.mjs '<js 表达式>' [监听秒数] [reload]
// 先 adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
const [expr = '1', secs = '3', reload] = process.argv.slice(2);
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = pages.find(p => p.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method === 'Runtime.consoleAPICalled') console.log('[console.' + m.params.type + ']', m.params.args.map(a => a.value ?? a.description).join(' ').slice(0, 300));
  if (m.method === 'Runtime.exceptionThrown') { const d = m.params.exceptionDetails; console.log('[exception]', (d.exception && d.exception.description || d.text).slice(0, 300), d.url || '', d.lineNumber); }
  if (m.method === 'Log.entryAdded') console.log('[log.' + m.params.entry.level + ']', m.params.entry.text.slice(0, 300), m.params.entry.url || '');
};
await new Promise(r => ws.onopen = r);
await send('Runtime.enable'); await send('Log.enable');
if (reload) await send('Page.reload', { ignoreCache: true });
await new Promise(r => setTimeout(r, +secs * 1000));
const res = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
console.log('[result]', JSON.stringify(res.result.result?.value ?? res.result.exceptionDetails?.exception?.description ?? res.result).slice(0, 3000));
ws.close(); process.exit(0);
