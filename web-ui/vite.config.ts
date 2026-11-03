import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  build: {
    target: 'chrome111',
    cssTarget: 'chrome111',
    outDir: '../app/src/main/assets/pocket-ui',
    emptyOutDir: true,
    manifest: true,
    sourcemap: false,
  },
});
