import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const stripApiPrefix = (path: string) => path.replace(/^\/api/, '');

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
  server: {
    // Routes like the ALB (infra/aws/main.tf); the first matching key wins.
    proxy: {
      // transaction-service: /transactions*, and the account fraud flags it stores.
      '^/api/(transactions|accounts/[^/?]+/flags)([/?]|$)': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        rewrite: stripApiPrefix,
      },
      // account-service: everything else (/accounts, /auth).
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: stripApiPrefix,
      },
    },
  },
});
