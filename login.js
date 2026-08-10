import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { config } from "./admin-site/config.js";

const supabase = createClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY, {
  auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
});

const $ = (id) => document.getElementById(id);
const dashboardUrl = new URL("./admin-site/", window.location.href).toString();

const { data: initial } = await supabase.auth.getSession();
if (initial.session) window.location.replace(dashboardUrl);

$("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  setError("");
  setBusy(true, "Entrando…");
  try {
    const { data, error } = await supabase.auth.signInWithPassword({
      email: $("email").value.trim(),
      password: $("password").value,
    });
    if (error || !data.session) throw error ?? new Error("No se pudo iniciar sesión.");
    window.location.replace(dashboardUrl);
  } catch (error) {
    setError(error?.message || "Correo o contraseña incorrectos.");
  } finally {
    setBusy(false, "Entrar");
  }
});

$("githubLogin").addEventListener("click", async () => {
  setError("");
  const button = $("githubLogin");
  button.disabled = true;
  button.textContent = "Abriendo GitHub…";
  const { error } = await supabase.auth.signInWithOAuth({
    provider: "github",
    options: { redirectTo: dashboardUrl, scopes: "read:user user:email" },
  });
  if (error) {
    setError("No fue posible iniciar sesión con GitHub.");
    button.disabled = false;
    button.textContent = "Entrar con GitHub";
  }
});

function setBusy(busy, label) {
  const button = $("loginButton");
  button.disabled = busy;
  button.textContent = label;
}

function setError(message) {
  const box = $("loginError");
  box.hidden = !message;
  box.textContent = message;
}
