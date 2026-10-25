import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    // Development: the browser talks only to Vite (http://localhost:5173), and Vite forwards
    // /api to Spring Boot. Same origin for the browser, so no CORS is involved at all.
    //
    // changeOrigin: false keeps the browser's Host header (localhost:5173). Browsers send an
    // Origin header on every POST, even to their own origin; if the proxy rewrote Host to
    // localhost:8080, Spring would see Origin http://localhost:5173 arriving at localhost:8080,
    // treat the request as CROSS-origin, and refuse it with 403. (Vite's short form
    // '/api': 'http://localhost:8080' does rewrite Host. That is exactly how this was found.)
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: false } },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // npm test: everything except the exercises, which fail until you do them.
    // npm run test:exercises (vitest --mode exercises): ONLY the exercises.
    include: mode === 'exercises' ? ['src/exercises/**/*.test.{ts,tsx}'] : ['src/**/*.test.{ts,tsx}'],
    exclude: mode === 'exercises' ? ['node_modules/**'] : ['src/exercises/**', 'node_modules/**'],
  },
}));
