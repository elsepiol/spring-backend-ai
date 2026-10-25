import { defineConfig, mergeConfig } from 'vitest/config';
import base from './vite.config';

/** npm run test:exercises : ONLY the exercise tests (see docs/learn/07-frontend.md). */
export default mergeConfig(base, defineConfig({
  test: {
    include: ['src/exercises/**/*.test.{ts,tsx}'],
    exclude: ['node_modules/**'],
  },
}));
