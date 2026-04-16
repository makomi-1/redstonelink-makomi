import { defineConfig } from 'vite';
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
});
