import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
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
    include: ['src/**/*.test.{ts,tsx}'],
    // Exercise tests fail until you do the exercise: run them with npm run test:exercises.
    exclude: ['src/exercises/**', 'node_modules/**'],
  },
});
