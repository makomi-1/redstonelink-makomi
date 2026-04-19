import { configDefaults, defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

const resolvedOutDir = process.env.REDSTONELINK_WEBAPP_OUT_DIR?.trim() || resolve(__dirname, 'dist');

export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: resolvedOutDir,
    emptyOutDir: true,
    sourcemap: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    restoreMocks: true,
    clearMocks: true,
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
});
