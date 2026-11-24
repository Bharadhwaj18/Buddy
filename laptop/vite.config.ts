import { defineConfig } from "vite";

// Tauri talks to this dev server on a fixed port.
export default defineConfig({
  clearScreen: false,
  server: { port: 1420, strictPort: true },
  build: { target: "es2022", outDir: "dist", emptyOutDir: true },
});
