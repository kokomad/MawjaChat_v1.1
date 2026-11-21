// MawjaChat Relay — خادم بث بسيط: غرف خاصة + حجز أسماء المستخدمين (اسم واحد = جهاز واحد)
// التشغيل:  npm install && npm start      (المنفذ من PORT أو 8080)
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const NAMES_FILE = process.env.NAMES_FILE || path.join(__dirname, 'names.json');
const NAME_TTL_MS = 30 * 24 * 3600 * 1000; // يُفرج عن الاسم بعد 30 يوماً من عدم الاستخدام

let names = {};
try { names = JSON.parse(fs.readFileSync(NAMES_FILE, 'utf8')); } catch (_) {}
const saveNames = () => { try { fs.writeFileSync(NAMES_FILE, JSON.stringify(names)); } catch (e) { console.error(e); } };

function claimName(user, device) {
  const key = user.trim().toLowerCase();
  const now = Date.now();
  const e = names[key];
  if (e && e.device !== device && now - e.lastSeen < NAME_TTL_MS) return false;
  names[key] = { device, lastSeen: now, display: user.trim() };
  saveNames();
  return true;
}

const rooms = new Map(); // roomId -> Set<ws>

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('MawjaChat relay OK\n');
});
const wss = new WebSocketServer({ server, maxPayload: 16 * 1024 * 1024 });

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://localhost');
  const room = url.searchParams.get('room');
  const user = (url.searchParams.get('user') || '').trim();
  const device = url.searchParams.get('device');
  if (!room || !user || !device) return ws.close(4000, 'bad_request');

  if (!claimName(user, device)) {
    ws.send(JSON.stringify({ t: 'error', code: 'name_taken' }));
    return ws.close(4001, 'name_taken');
  }

  ws.meta = { room, user, device, alive: true };
  if (!rooms.has(room)) rooms.set(room, new Set());
  rooms.get(room).add(ws);

  ws.on('pong', () => { ws.meta.alive = true; });

  ws.on('message', (data) => {
    const text = data.toString();
    for (const peer of rooms.get(room) || []) {
      if (peer !== ws && peer.readyState === 1) peer.send(text);
    }
  });

  ws.on('close', () => {
    const set = rooms.get(room);
    if (!set) return;
    set.delete(ws);
    const stillHere = [...set].some((p) => p.meta.device === device);
    if (!stillHere) {
      const bye = JSON.stringify({ t: 'bye', user, device });
      for (const peer of set) if (peer.readyState === 1) peer.send(bye);
    }
    if (set.size === 0) rooms.delete(room);
  });
});

// نبض: إنهاء الاتصالات الميتة وتجديد "آخر ظهور" للأسماء المتصلة
setInterval(() => {
  let touched = false;
  for (const ws of wss.clients) {
    if (!ws.meta) continue;
    if (!ws.meta.alive) { ws.terminate(); continue; }
    ws.meta.alive = false;
    ws.ping();
    const e = names[ws.meta.user.toLowerCase()];
    if (e && e.device === ws.meta.device) { e.lastSeen = Date.now(); touched = true; }
  }
  if (touched) saveNames();
}, 30000);

server.listen(PORT, () => console.log(`MawjaChat relay listening on :${PORT}`));
