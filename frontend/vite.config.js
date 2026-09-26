import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],

  server: {
    // Auth0 is configured for exactly this origin, so the port is not
    // negotiable: a fallback port would fail the callback URL check.
    port: 5173,
    strictPort: true,

    // The API Gateway already allows any origin, so calls could go direct.
    // They go through this proxy anyway, so that a browser request in
    // development has the same origin as one in a build served behind a
    // reverse proxy, and nothing depends on permissive CORS staying that way.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
