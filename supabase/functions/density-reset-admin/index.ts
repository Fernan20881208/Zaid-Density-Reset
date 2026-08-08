const ASSETS = new Map<string, { file: string; type: string }>([
  ["/", { file: "index.html", type: "text/html; charset=utf-8" }],
  ["/index.html", { file: "index.html", type: "text/html; charset=utf-8" }],
  ["/app.js", { file: "app.js", type: "text/javascript; charset=utf-8" }],
  ["/github-auth.js", { file: "github-auth.js", type: "text/javascript; charset=utf-8" }],
  ["/styles.css", { file: "styles.css", type: "text/css; charset=utf-8" }],
  ["/config.js", { file: "config.js", type: "text/javascript; charset=utf-8" }],
]);

const RAW_BASE = "https://raw.githubusercontent.com/Fernan20881208/Zaid-Density-Reset/main/supabase/functions/density-reset-admin";
const MARKER = "/density-reset-admin";

Deno.serve(async (request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  try {
    const url = new URL(request.url);
    const markerIndex = url.pathname.indexOf(MARKER);
    const path = markerIndex >= 0
      ? url.pathname.slice(markerIndex + MARKER.length) || "/"
      : url.pathname;

    const asset = ASSETS.get(path);
    if (!asset) return new Response("Not found", { status: 404 });

    const upstream = await fetch(`${RAW_BASE}/${asset.file}`, {
      headers: {
        Accept: "text/plain,*/*",
        "User-Agent": "Density-Reset-Admin",
      },
    });

    if (!upstream.ok) {
      return new Response("No fue posible cargar el panel.", { status: 502 });
    }

    const body = request.method === "HEAD" ? null : await upstream.text();
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": asset.type,
        "Cache-Control": path === "/" || path === "/index.html" ? "no-store" : "public, max-age=60",
        "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": "default-src 'self'; script-src 'self' https://esm.sh; style-src 'self' 'unsafe-inline'; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
      },
    });
  } catch (_error) {
    return new Response("No fue posible cargar el panel.", { status: 500 });
  }
});
