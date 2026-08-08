import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { config } from "./config.js";

const supabase = createClient(config.SUPABASE_URL, config.SUPABASE_ANON_KEY, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true,
  },
});

const button = document.getElementById("githubLogin");
const errorView = document.getElementById("loginError");

button?.addEventListener("click", async () => {
  button.disabled = true;
  errorView.hidden = true;

  const redirectTo = `${window.location.origin}${window.location.pathname}`;
  const { error } = await supabase.auth.signInWithOAuth({
    provider: "github",
    options: {
      redirectTo,
      scopes: "read:user user:email",
    },
  });

  if (error) {
    errorView.textContent = "No fue posible iniciar sesión con GitHub.";
    errorView.hidden = false;
    button.disabled = false;
  }
});
