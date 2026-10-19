import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
export default defineConfig({
  plugins: [
    react(),
    {
      name: 'smartmeal-shell-version',
      writeBundle(options) {
        const output = resolve(options.dir || 'dist');
        const hash = createHash('sha256')
          .update(readFileSync(resolve(output, 'index.html')))
          .digest('hex')
          .slice(0, 12);
        const path = resolve(output, 'sw.js');
        writeFileSync(
          path,
          readFileSync(path, 'utf8').replace('smartmeal-shell-v2', `smartmeal-shell-${hash}`),
        );
      },
    },
  ],
  server: {
    proxy: {
      '^/logout$': {
        target: process.env.SMARTMEAL_API_URL || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/api': {
        target: process.env.SMARTMEAL_API_URL || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  preview: { port: 4173 },
});
