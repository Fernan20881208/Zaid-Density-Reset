const ASSET_BASE = "https://cdn.jsdelivr.net/gh/Fernan20881208/Zaid-Density-Reset@fb0bbc07a9f49d2643b05868928a95a46dbd857a/supabase/functions/density-reset-admin";

const HTML = `<!doctype html>
<html lang="es">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
  <meta name="color-scheme" content="dark light" />
  <title>Density Reset Admin</title>
  <link rel="stylesheet" href="${ASSET_BASE}/styles.css" />
</head>
<body>
  <main class="shell">
    <section id="loginView" class="glass auth-card">
      <div class="eyebrow">DENSITY RESET</div>
      <h1>Density Reset Admin</h1>
      <p>Acceso privado mediante Supabase Auth.</p>
      <form id="loginForm" autocomplete="on">
        <label>Correo
          <input id="email" type="email" autocomplete="username" required />
        </label>
        <label>Contraseña
          <input id="password" type="password" autocomplete="current-password" required />
        </label>
        <button type="submit">Entrar</button>
        <div class="auth-divider"><span>o</span></div>
        <button id="githubLogin" type="button" class="secondary">Entrar con GitHub</button>
        <div id="loginError" class="message error" hidden></div>
      </form>
    </section>

    <section id="adminView" hidden>
      <header class="glass topbar">
        <div><div class="eyebrow">DENSITY RESET</div><h1>Density Reset Admin</h1><p id="adminIdentity"></p></div>
        <button id="logoutAdmin" class="secondary">Cerrar sesión</button>
      </header>

      <section class="stats">
        <article class="glass stat"><span>Activas</span><strong id="countActive">—</strong></article>
        <article class="glass stat"><span>Sin activar</span><strong id="countUnused">—</strong></article>
        <article class="glass stat"><span>Expiradas</span><strong id="countExpired">—</strong></article>
        <article class="glass stat"><span>Revocadas</span><strong id="countRevoked">—</strong></article>
        <article class="glass stat"><span>Deshabilitadas</span><strong id="countDisabled">—</strong></article>
      </section>

      <section class="glass generator">
        <div class="section-heading"><div><h2>Generar licencia</h2><p>Las keys completas solo se muestran una vez.</p></div></div>
        <form id="generatorForm" class="form-grid">
          <label>Cantidad<input id="quantity" type="number" min="1" max="100" value="1" required /></label>
          <label>Duración<select id="duration"><option value="1">1 día</option><option value="3">3 días</option><option value="7">7 días</option><option value="15">15 días</option><option value="30" selected>30 días</option><option value="90">90 días</option><option value="365">365 días</option><option value="permanent">Permanente</option><option value="custom">Personalizado</option></select></label>
          <label id="customDurationField" hidden>Días personalizados<input id="customDuration" type="number" min="1" max="3650" value="30" /></label>
          <label>Inicio de duración<select id="durationStartMode"><option value="first_activation" selected>Primera activación</option><option value="generation">Desde generación</option></select></label>
          <label>Dispositivos permitidos<input id="maxDevices" type="number" min="1" max="100" value="1" required /></label>
          <label>Etiqueta<input id="label" type="text" maxlength="120" placeholder="Cliente TikTok 07" /></label>
          <label class="wide">Notas<textarea id="notes" maxlength="1000" placeholder="Key entregada el 8 de agosto."></textarea></label>
          <div class="wide actions-row"><button id="generateButton" type="submit">Generar</button></div>
        </form>
        <div id="generationResult" class="generation-result" hidden>
          <div class="message warning">Guarda estas keys ahora. Por seguridad no podrán volver a mostrarse completas.</div>
          <pre id="generatedKeys"></pre>
          <div class="actions-row"><button id="copyAll" type="button">Copiar todas</button><button id="exportCsv" type="button" class="secondary">Exportar CSV</button></div>
        </div>
      </section>

      <section class="glass licenses-section">
        <div class="section-heading responsive-heading">
          <div><h2>Licencias</h2><p id="licenseTotal">0 resultados</p></div>
          <div class="toolbar-controls"><input id="search" type="search" placeholder="Buscar key, etiqueta o dispositivo" /><select id="statusFilter"><option value="all">Todas</option><option value="active">Activas</option><option value="unused">Sin activar</option><option value="expired">Expiradas</option><option value="revoked">Revocadas</option><option value="disabled">Deshabilitadas</option></select></div>
        </div>
        <div id="tableError" class="message error" hidden></div>
        <div class="table-wrap"><table><thead><tr><th>Key</th><th>Estado</th><th>Duración</th><th>Creada</th><th>Activada</th><th>Expira</th><th>Dispositivo</th><th>Última validación</th><th>Acciones</th></tr></thead><tbody id="licensesBody"></tbody></table></div>
      </section>
    </section>
  </main>

  <dialog id="detailsDialog" class="glass dialog-card"><form method="dialog"><div class="section-heading"><h2>Detalles de licencia</h2><button value="cancel" class="secondary compact">Cerrar</button></div><div id="detailsContent"></div></form></dialog>
  <script type="module" src="${ASSET_BASE}/github-auth.js"></script>
  <script type="module" src="${ASSET_BASE}/app.js"></script>
</body>
</html>`;

Deno.serve((request) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405 });
  }

  const url = new URL(request.url);
  if (url.pathname.endsWith("/health")) {
    return new Response(JSON.stringify({ ok: true, service: "density-reset-admin", version: 10 }), {
      status: 200,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      },
    });
  }

  return new Response(request.method === "HEAD" ? null : HTML, {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "Content-Security-Policy": "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net https://esm.sh; style-src 'self' https://cdn.jsdelivr.net; connect-src 'self' https://zzlvupunploglgxbgllm.supabase.co https://esm.sh; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    },
  });
});
