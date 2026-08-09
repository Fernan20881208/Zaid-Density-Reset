const BASE_PATH = "/functions/v1/density-reset-admin";
const FUNCTION_MARKER = "/density-reset-admin";
const RAW_BASE = "https://raw.githubusercontent.com/Fernan20881208/Zaid-Density-Reset/main/supabase/functions/density-reset-admin";

const commonHeaders = {
  "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
  "Pragma": "no-cache",
  "Expires": "0",
  "Referrer-Policy": "no-referrer",
};

async function loadAsset(name: string): Promise<string> {
  const response = await fetch(`${RAW_BASE}/${name}`, {
    headers: { "Accept": "text/plain,*/*" },
    cache: "no-store",
  });
  if (!response.ok) {
    throw new Error(`Unable to load ${name}: ${response.status}`);
  }
  return await response.text();
}

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

function routeSuffix(pathname: string): string {
  if (!pathname || pathname === "/") return "/";

  const markerIndex = pathname.lastIndexOf(FUNCTION_MARKER);
  if (markerIndex >= 0) {
    const suffix = pathname.slice(markerIndex + FUNCTION_MARKER.length);
    return suffix || "/";
  }

  // Supabase Edge Runtime may provide a pathname already stripped of the
  // public /functions/v1/<function-name> prefix. Support that form too.
  return pathname.startsWith("/") ? pathname : `/${pathname}`;
}

Deno.serve(async (request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  const { pathname } = new URL(request.url);
  const suffix = routeSuffix(pathname);

  if (suffix === "/health") {
    return Response.json(
      { ok: true, service: "density-reset-admin", version: 17 },
      { headers: { "Cache-Control": "no-store" } },
    );
  }

  try {
    if (suffix === "" || suffix === "/") {
      const source = await loadAsset("index.html");
      const html = source
        .replace('href="./styles.css"', `href="${BASE_PATH}/styles.css"`)
        .replace('src="./github-auth.js"', `src="${BASE_PATH}/github-auth.js"`)
        .replace('src="./app.js"', `src="${BASE_PATH}/app.js"`);

      const response = assetResponse(
        html,
        "text/html; charset=utf-8",
        request.method,
      );
      response.headers.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self' https://esm.sh; style-src 'self'; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
      );
      return response;
    }

    const assets: Record<string, [string, string]> = {
      "/styles.css": ["styles.css", "text/css; charset=utf-8"],
      "/app.js": ["app.js", "text/javascript; charset=utf-8"],
      "/github-auth.js": ["github-auth.js", "text/javascript; charset=utf-8"],
      "/config.js": ["config.js", "text/javascript; charset=utf-8"],
    };

    const asset = assets[suffix];
    if (!asset) {
      return new Response("Not found", {
        status: 404,
        headers: { "Content-Type": "text/plain; charset=utf-8" },
      });
    }

    const body = await loadAsset(asset[0]);
    return assetResponse(body, asset[1], request.method);
  } catch (error) {
    console.error(
      "density-reset-admin asset error",
      error instanceof Error ? error.message : "unknown",
    );
    return new Response(
      "Density Reset Admin no pudo cargar sus recursos. Intenta nuevamente en unos segundos.",
      {
        status: 503,
        headers: {
          ...commonHeaders,
          "Content-Type": "text/plain; charset=utf-8",
        },
      },
    );
  }
});
