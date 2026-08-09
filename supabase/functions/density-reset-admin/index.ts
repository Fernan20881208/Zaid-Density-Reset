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

Deno.serve((request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  const url = new URL(request.url);
  if (url.pathname.endsWith("/health")) {
    return Response.json(
      { ok: true, service: "density-reset-admin", version: 12 },
      { headers: { "Cache-Control": "no-store" } },
    );
  }

  return new Response(request.method === "HEAD" ? null : HTML, {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=UTF-8",
      "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
      "Pragma": "no-cache",
      "Expires": "0",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "Content-Security-Policy": "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net https://esm.sh; style-src 'self' https://cdn.jsdelivr.net; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    },
  });
});
