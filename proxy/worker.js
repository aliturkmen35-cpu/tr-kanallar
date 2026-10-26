// Alin's TV web aktarma sunucusu (Cloudflare Worker)
// Bazı kanalların sunucusu (ör. Show TV) yayın listesini (.m3u8) yalnızca kendi
// sitesindeki tarayıcılara veriyor. Bu sunucu yalnızca o listeleri aktarır;
// görüntü parçaları (.ts) doğrudan kanalın kendi sunucusundan yüklenir.

const ALLOWED_HOSTS = [/(^|\.)daioncdn\.net$/];
// Sunucu adına göre kanalın kendi sitesi (en uzun eşleşme kazanır)
const REFERERS = {
  "ciner.daioncdn.net": "https://www.showtv.com.tr/",
  "demiroren.daioncdn.net": "https://www.kanald.com.tr/",
  "dogus.daioncdn.net": "https://www.ntv.com.tr/",
  "daioncdn.net": "https://www.showtv.com.tr/",
};

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "*",
};

export default {
  async fetch(req) {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const self = new URL(req.url);
    const raw = self.searchParams.get("u");
    if (!raw) return new Response("Alin's TV proxy", { headers: CORS });

    let target;
    try { target = new URL(raw); } catch { return new Response("bad url", { status: 400, headers: CORS }); }
    if (target.protocol !== "https:" || !ALLOWED_HOSTS.some((re) => re.test(target.hostname))) {
      return new Response("forbidden", { status: 403, headers: CORS });
    }

    const refKey = Object.keys(REFERERS)
      .filter((d) => target.hostname === d || target.hostname.endsWith("." + d))
      .sort((a, b) => b.length - a.length)[0];
    const referer = refKey ? REFERERS[refKey] : "";
    const up = await fetch(target.href, {
      headers: {
        "User-Agent": req.headers.get("User-Agent") || "Mozilla/5.0",
        ...(referer ? { Referer: referer, Origin: new URL(referer).origin } : {}),
      },
    });

    const type = up.headers.get("Content-Type") || "";
    const isPlaylist = /mpegurl/i.test(type) || /\.m3u8$/i.test(target.pathname);
    if (!isPlaylist) {
      const h = new Headers(up.headers);
      for (const [k, v] of Object.entries(CORS)) h.set(k, v);
      return new Response(up.body, { status: up.status, headers: h });
    }

    // Listedeki adresleri tam adrese çevir; alt listeler (.m3u8) yine bu sunucudan geçsin
    const base = up.url || target.href;
    const wrap = (u) => {
      const abs = new URL(u, base);
      return /\.m3u8$/i.test(abs.pathname)
        ? `${self.origin}/?u=${encodeURIComponent(abs.href)}`
        : abs.href;
    };
    const text = await up.text();
    const out = text.split(/\r?\n/).map((line) => {
      const l = line.trim();
      if (!l) return line;
      if (l.startsWith("#")) return l.replace(/URI="([^"]+)"/g, (_, u) => `URI="${wrap(u)}"`);
      return wrap(l);
    }).join("\n");

    return new Response(out, {
      status: up.status,
      headers: { ...CORS, "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-store" },
    });
  },
};
