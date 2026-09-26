import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export function createConfig(segment: string) {
  return defineConfig(({ command }) => {
    if (command === 'serve') {
      // Standalone dev server — renders the component in isolation with HMR.
      // Run via: npm run dev:superuser
      //
      // API calls are proxied to the running Elements instance so that relative paths like
      // /cloud-connect/api/connect resolve correctly regardless of the dev server port. Override
      // the target with the ELEMENTS_URL env var:
      //   ELEMENTS_URL=http://localhost:9090 npm run dev:superuser
      const elementsUrl = process.env.ELEMENTS_URL ?? 'http://localhost:8080'
      return {
        plugins: [react({ jsxRuntime: 'classic' })],
        root: `src/${segment}`,
        server: {
          proxy: {
            '/api': elementsUrl,
            '/app': elementsUrl,
            '/cloud-connect': elementsUrl,
          },
        },
      }
    }

    // Library/IIFE build — writes plugin.bundle.js directly into
    // ../element/src/main/ui/{segment}/ for packaging into the .elm artifact.
    // Run via: npm run build
    return {
      esbuild: {
        jsx: 'transform',
        jsxFactory: 'React.createElement',
        jsxFragment: 'React.Fragment',
      },
      build: {
        lib: {
          entry: `src/${segment}/plugin-entry.ts`,
          name: 'ElementPlugin',
          formats: ['iife' as const],
          fileName: () => 'plugin.bundle.js',
        },
        outDir: `../element/src/main/ui/${segment}`,
        emptyOutDir: false,
        minify: false,
        // Base64-inline the logo rather than emitting a sibling asset file, so plugin.bundle.js
        // stays a single self-contained file exactly as the dashboard plugin contract requires.
        assetsInlineLimit: 1024 * 1024,
        rollupOptions: {
          external: ['react'],
          output: {
            // Rewrites `import React from 'react'` → `var React = window.React` in the IIFE.
            globals: { react: 'window.React' },
          },
        },
      },
    }
  })
}
