const REV = "fb0bbc07a9f49d2643b05868928a95a46dbd857a";
const ASSET_BASE = `https://cdn.jsdelivr.net/gh/Fernan20881208/Zaid-Density-Reset@${REV}/supabase/functions/density-reset-admin`;

const source = await fetch(`${ASSET_BASE}/index.html`, {
  headers: { "Accept": "text/html" },
});
if (!source.ok) throw new Error(`admin html unavailable: ${source.status}`);

const HTML = (await source.text())
  .replace('href="./styles.css"', `href="${ASSET_BASE}/styles.css"`)
  .replace('src="./github-auth.js"', `src="${ASSET_BASE}/github-auth.js"`)
  .replace('src="./app.js"', `src="${ASSET_BASE}/app.js"`);

const HTML_BLOB = new Blob([HTML], { type: "text/html;charset=utf-8" });

Deno.serve((request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  const url = new URL(request.url);
  if (url.pathname.endsWith("/health")) {
    return Response.json(
      { ok: true, service: "density-reset-admin", version: 13 },
      { headers: { "Cache-Control": "no-store" } },
    );
  }

  const headers = new Headers();
  headers.set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
  headers.set("Pragma", "no-cache");
  headers.set("Expires", "0");
  headers.set("Content-Disposition", "inline");
  headers.set("Referrer-Policy", "no-referrer");
  headers.set(
    "Content-Security-Policy",
    "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net https://esm.sh; style-src 'self' https://cdn.jsdelivr.net; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
  );

  if (request.method === "HEAD") {
    headers.set("Content-Type", "text/html; charset=utf-8");
    return new Response(null, { status: 200, headers });
  }

  return new Response(HTML_BLOB, {
    status: 200,
    headers,
  });
});
