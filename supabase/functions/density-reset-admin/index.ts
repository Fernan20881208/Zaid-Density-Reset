const assets = new Map<string, { file: string; type: string }>([
  ["/", { file: "index.html", type: "text/html; charset=utf-8" }],
  ["/index.html", { file: "index.html", type: "text/html; charset=utf-8" }],
  ["/app.js", { file: "app.js", type: "text/javascript; charset=utf-8" }],
  ["/github-auth.js", { file: "github-auth.js", type: "text/javascript; charset=utf-8" }],
  ["/styles.css", { file: "styles.css", type: "text/css; charset=utf-8" }],
  ["/config.js", { file: "config.js", type: "text/javascript; charset=utf-8" }],
]);

Deno.serve(async (request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  const url = new URL(request.url);
  const marker = "/density-reset-admin";
  const markerIndex = url.pathname.indexOf(marker);
  const path = markerIndex >= 0
    ? url.pathname.slice(markerIndex + marker.length) || "/"
    : url.pathname;
  const asset = assets.get(path);
  if (!asset) return new Response("Not found", { status: 404 });

  const body = await Deno.readTextFile(new URL(asset.file, import.meta.url));
  return new Response(request.method === "HEAD" ? null : body, {
    headers: {
      "Content-Type": asset.type,
      "Cache-Control": path === "/" || path === "/index.html" ? "no-store" : "public, max-age=300",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "Content-Security-Policy": "default-src 'self'; script-src 'self' https://esm.sh; style-src 'self' 'unsafe-inline'; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
    },
  });
});
