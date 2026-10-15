import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `npm run dev` proxies the API to a port-forwarded hub: kubectl -n rpe-system port-forward svc/rpe-hub 8800:80
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { "/api": "http://localhost:8800" } },
});
