const BASE_PATH = "/functions/v1/density-reset-admin";

const htmlSource = Deno.readTextFileSync(new URL("./index.html", import.meta.url));
const styles = Deno.readTextFileSync(new URL("./styles.css", import.meta.url));
const appJs = Deno.readTextFileSync(new URL("./app.js", import.meta.url));
const githubAuthJs = Deno.readTextFileSync(new URL("./github-auth.js", import.meta.url));
const configJs = Deno.readTextFileSync(new URL("./config.js", import.meta.url));

const html = htmlSource
  .replace('href="./styles.css"', `href="${BASE_PATH}/styles.css"`)
  .replace('src="./github-auth.js"', `src="${BASE_PATH}/github-auth.js"`)
  .replace('src="./app.js"', `src="${BASE_PATH}/app.js"`);

const commonHeaders = {
  "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
  "Pragma": "no-cache",
  "Expires": "0",
  "Referrer-Policy": "no-referrer",
};

function assetResponse(
  body: string,
  contentType: string,
  requestMethod: string,
): Response {
  return new Response(requestMethod === "HEAD" ? null : body, {
    status: 200,
    headers: {
      ...commonHeaders,
      "Content-Type": contentType,
    },
  });
}

Deno.serve((request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  const { pathname } = new URL(request.url);
  const suffix = pathname.startsWith(BASE_PATH)
    ? pathname.slice(BASE_PATH.length)
    : pathname;

  if (suffix === "/health") {
    return Response.json(
      { ok: true, service: "density-reset-admin", version: 14 },
      { headers: { "Cache-Control": "no-store" } },
    );
  }

  if (suffix === "" || suffix === "/") {
    const response = assetResponse(html, "text/html; charset=utf-8", request.method);
    response.headers.set(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'self' https://esm.sh; style-src 'self'; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    );
    return response;
  }

  switch (suffix) {
    case "/styles.css":
      return assetResponse(styles, "text/css; charset=utf-8", request.method);
    case "/app.js":
      return assetResponse(appJs, "text/javascript; charset=utf-8", request.method);
    case "/github-auth.js":
      return assetResponse(githubAuthJs, "text/javascript; charset=utf-8", request.method);
    case "/config.js":
      return assetResponse(configJs, "text/javascript; charset=utf-8", request.method);
    default:
      return new Response("Not found", {
        status: 404,
        headers: { "Content-Type": "text/plain; charset=utf-8" },
      });
  }
});
