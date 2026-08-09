const read = (name: string) => Deno.readTextFileSync(new URL(`./${name}`, import.meta.url));

const ASSETS = new Map<string, { body: string; type: string }>([
  ["/", { body: read("index.html"), type: "text/html; charset=utf-8" }],
  ["/index.html", { body: read("index.html"), type: "text/html; charset=utf-8" }],
  ["/app.js", { body: read("app.js"), type: "text/javascript; charset=utf-8" }],
  ["/github-auth.js", { body: read("github-auth.js"), type: "text/javascript; charset=utf-8" }],
  ["/styles.css", { body: read("styles.css"), type: "text/css; charset=utf-8" }],
  ["/config.js", { body: read("config.js"), type: "text/javascript; charset=utf-8" }],
]);

const MARKER = "/density-reset-admin";

Deno.serve((request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  const url = new URL(request.url);
  const markerIndex = url.pathname.indexOf(MARKER);
  const path = markerIndex >= 0
    ? url.pathname.slice(markerIndex + MARKER.length) || "/"
    : url.pathname;

  if (path === "/health") {
    return new Response(JSON.stringify({ ok: true, service: "density-reset-admin" }), {
      status: 200,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      },
    });
  }

  const asset = ASSETS.get(path);
  if (!asset) return new Response("Not found", { status: 404 });

  return new Response(request.method === "HEAD" ? null : asset.body, {
    status: 200,
    headers: {
      "Content-Type": asset.type,
      "Cache-Control": path === "/" || path === "/index.html" ? "no-store" : "public, max-age=300",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "Content-Security-Policy": "default-src 'self'; script-src 'self' https://esm.sh; style-src 'self' 'unsafe-inline'; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
    },
  });
});
