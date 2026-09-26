import { CloudConnectPlugin } from './CloudConnectPlugin'

declare const window: Window & {
  __elementsPlugins?: {
    register(route: string, component: unknown): void
  }
}

// Must match the `route` in element/src/main/ui/superuser/plugin.json, which is also the
// dashboard URL the sidebar entry links to (/plugin/namazu-cloud-connect).
window.__elementsPlugins?.register('namazu-cloud-connect', CloudConnectPlugin)
