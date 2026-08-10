const SITE_URL = "https://fernan20881208.github.io/Zaid-Density-Reset/";

Deno.serve((request) => {
  const url = new URL(request.url);
  if (url.pathname.endsWith("/health")) {
    return Response.json(
      { ok: true, service: "density-reset-admin", mode: "redirect", target: SITE_URL, version: 18 },
      { headers: { "Cache-Control": "no-store" } },
    );
  }

  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", {
      status: 405,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });
  }

  return new Response(null, {
    status: 302,
    headers: {
      "Location": SITE_URL,
      "Cache-Control": "no-store",
      "Referrer-Policy": "no-referrer",
    },
  });
});
