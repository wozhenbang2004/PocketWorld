import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Reuse the exact, already vendored display-only xterm assets used by native SSH.
const terminalFiles = ['xterm.js', 'xterm.css', 'terminal-policy.js', 'LICENSE.xterm.txt', 'THIRD_PARTY.json'];
const terminalAsset = (name: string) => readFileSync(fileURLToPath(new URL('../app/src/main/assets/ssh-terminal/' + name, import.meta.url)));

export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss(), {
    name: 'existing-private-terminal-assets',
    generateBundle() { for (const name of terminalFiles) this.emitFile({ type: 'asset', fileName: 'ssh-terminal/' + name, source: terminalAsset(name) }); },
    configureServer(server) { server.middlewares.use((request, response, next) => {
      const name = request.url?.replace(/^\/ssh-terminal\//, '');
      if (!request.url?.startsWith('/ssh-terminal/') || !name || !terminalFiles.includes(name)) { next(); return; }
      response.setHeader('Content-Type', name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/plain'); response.end(terminalAsset(name));
    }); },
  }],
  build: {
    target: 'chrome111',
    cssTarget: 'chrome111',
    outDir: '../app/src/main/assets/pocket-ui',
    emptyOutDir: true,
    // AAPT filters dot-prefixed assets; the receipt-listed manifest must stay visible.
    manifest: 'manifest.json',
    sourcemap: false,
  },
});
