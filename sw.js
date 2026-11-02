// Service worker: يجعل التطبيق يفتح دون إنترنت (القاموس ولوحة أمي تعمل دون اتصال)
const CACHE = "sila-v4";
const SHELL = ["./", "./index.html", "./manifest.webmanifest",
  "./icons/icon-192.png", "./icons/icon-512.png", "./icons/icon-maskable-512.png", "./icons/apple-touch-icon.png",
  "./fonts/ge-ss-two-light.otf", "./fonts/ge-ss-two-medium.otf", "./fonts/ge-ss-two-bold.otf"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;               // طلبات الذكاء الاصطناعي (POST) تذهب للشبكة مباشرة
  const url = new URL(req.url);
  if (url.origin === location.origin) {
    // الشبكة أولاً لنحصل على آخر نسخة، ثم النسخة المحفوظة عند انقطاع الإنترنت
    e.respondWith(fetch(req).then(res => {
      const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return res;
    }).catch(() => caches.match(req).then(r => r || caches.match("./index.html"))));
  }
});
