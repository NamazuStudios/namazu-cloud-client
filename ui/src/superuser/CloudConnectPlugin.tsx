import React from 'react'
import logo from '../app/assets/logo.png'

// ── Elements dashboard plumbing ───────────────────────────────────────────────

declare global {
  interface Window {
    __elementsApiClient?: {
      getSessionToken(): string | null
    }
  }
}

function getSessionToken(): string {
  return window.__elementsApiClient?.getSessionToken() ?? ''
}

// ── API ───────────────────────────────────────────────────────────────────────

/** RS_ROOT in CloudClientApplication.kt. Kept in one place; change it there and here together. */
const API_ROOT = '/cloud-connect/api'

interface ErrorBody {
  message?: string
  error?: string
}

async function apiFetch<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'Elements-SessionSecret': getSessionToken(),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const data: unknown = await res.json().catch(() => ({}))
  if (!res.ok) {
    const err = data as ErrorBody
    throw new Error(err.message ?? err.error ?? `Request failed (${res.status})`)
  }
  return data as T
}

// ── Types ─────────────────────────────────────────────────────────────────────

type ConnectState = 'CONNECTED' | 'CONNECTING' | 'DISCONNECTED' | 'NOT_CONFIGURED' | 'DISABLED'

interface ParameterValue {
  key: string
  value: string
  source: 'ENVIRONMENT' | 'ATTRIBUTE'
  envKey: string
  overriddenByEnvironment: boolean
}

interface ConnectParameters {
  url: string
  controlUri: string
  clientId: string
  secret: string
  secretConfigured: boolean
  retrySeconds: number
  sessionTtlMinutes: number
  values: ParameterValue[]
}

interface ConnectStatus {
  enabled: boolean
  state: ConnectState
  connected: boolean
  detail: string
  lastConnectedAt: number | null
  lastDisconnectedAt: number | null
  lastError: string | null
  updatedAt: number | null
  parameters: ConnectParameters
}

// ── Status light ──────────────────────────────────────────────────────────────

/**
 * Green means authenticated and serving. Red covers every state where Namazu Cloud has no
 * usable channel. Amber marks a connection in flight, and grey means a superuser deliberately
 * turned the connector off — neither of which is a fault.
 */
type LightTone = 'green' | 'red' | 'amber' | 'grey'

const LIGHT_DOT: Record<LightTone, string> = {
  green: 'bg-emerald-500',
  red: 'bg-red-500',
  amber: 'bg-amber-500',
  grey: 'bg-muted-foreground/40',
}

const LIGHT_RING: Record<LightTone, string> = {
  green: 'bg-emerald-500/15',
  red: 'bg-red-500/15',
  amber: 'bg-amber-500/15',
  grey: 'bg-muted-foreground/10',
}

const LIGHT_TEXT: Record<LightTone, string> = {
  green: 'text-emerald-600 dark:text-emerald-400',
  red: 'text-red-600 dark:text-red-400',
  amber: 'text-amber-600 dark:text-amber-400',
  grey: 'text-muted-foreground',
}

const STATE_LIGHT: Record<ConnectState, LightTone> = {
  CONNECTED: 'green',
  CONNECTING: 'amber',
  DISCONNECTED: 'red',
  NOT_CONFIGURED: 'red',
  DISABLED: 'grey',
}

const STATE_LABEL: Record<ConnectState, string> = {
  CONNECTED: 'Connected',
  CONNECTING: 'Connecting',
  DISCONNECTED: 'Disconnected',
  NOT_CONFIGURED: 'Not configured',
  DISABLED: 'Disabled',
}

const POLL_INTERVAL_MS = 5000

function formatTimestamp(epochMillis: number | null): string {
  if (!epochMillis) return '—'
  return new Date(epochMillis).toLocaleString()
}

// ── Small presentational pieces ───────────────────────────────────────────────

function StatusLight({ state }: { state: ConnectState }) {
  const tone = STATE_LIGHT[state] ?? 'grey'
  return (
    <span className="relative inline-flex h-3 w-3 shrink-0" role="img" aria-label={STATE_LABEL[state]}>
      {/* The softer ring behind the dot keeps the light legible on both light and dark chrome. */}
      <span className={`absolute inset-0 rounded-full ${LIGHT_RING[tone]}`} />
      <span className={`relative h-3 w-3 rounded-full ${LIGHT_DOT[tone]}`} />
    </span>
  )
}

function ParameterRow({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex flex-col gap-0.5 border-b border-border/60 py-2.5 last:border-b-0 sm:flex-row sm:items-baseline sm:gap-4">
      <span className="shrink-0 text-xs font-medium uppercase tracking-wide text-muted-foreground sm:w-44">
        {label}
      </span>
      <span className="min-w-0 flex-1">
        <span className="break-all font-mono text-sm">{value}</span>
        {hint && <span className="mt-0.5 block text-xs text-muted-foreground">{hint}</span>}
      </span>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-border bg-card p-4">
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h2>
      {children}
    </section>
  )
}

// ── Plugin ────────────────────────────────────────────────────────────────────

export function CloudConnectPlugin() {
  const [status, setStatus] = React.useState<ConnectStatus | null>(null)
  const [error, setError] = React.useState<string | null>(null)
  const [loading, setLoading] = React.useState(true)
  const [busy, setBusy] = React.useState(false)
  const [revealedSecret, setRevealedSecret] = React.useState<string | null>(null)
  const [revealBusy, setRevealBusy] = React.useState(false)

  const refresh = React.useCallback(async () => {
    try {
      setStatus(await apiFetch<ConnectStatus>('GET', `${API_ROOT}/connect`))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  React.useEffect(() => {
    void refresh()
    const timer = setInterval(() => void refresh(), POLL_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [refresh])

  // Any toggle changes the secret's exposure context, so drop a previously revealed value rather
  // than leaving it on screen after the operator's intent changed.
  React.useEffect(() => {
    setRevealedSecret(null)
  }, [status?.enabled, status?.state])

  async function toggle() {
    if (!status) return
    setBusy(true)
    try {
      setStatus(await apiFetch<ConnectStatus>('POST', `${API_ROOT}/connect/enabled`, { enabled: !status.enabled }))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function toggleSecret() {
    if (revealedSecret !== null) {
      setRevealedSecret(null)
      return
    }
    setRevealBusy(true)
    try {
      const body = await apiFetch<{ secret: string }>('GET', `${API_ROOT}/connect/secret`)
      setRevealedSecret(body.secret)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setRevealBusy(false)
    }
  }

  const tone = status ? STATE_LIGHT[status.state] ?? 'grey' : 'grey'
  const secretShown = revealedSecret ?? ''

  return (
    <div className="mx-auto max-w-3xl space-y-4 p-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <img src={logo} alt="Namazu Cloud" className="h-11 w-auto" />
          <div>
            <h1 className="text-xl font-semibold leading-tight">Namazu Cloud Connect</h1>
            <p className="text-sm text-muted-foreground">
              The client connector between this instance and Namazu Cloud.
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          {status && (
            <div className="flex items-center gap-2">
              <StatusLight state={status.state} />
              <span className={`text-sm font-medium ${LIGHT_TEXT[tone]}`}>{STATE_LABEL[status.state]}</span>
            </div>
          )}
          <button
            onClick={toggle}
            disabled={!status || busy}
            className={`rounded-md px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50 ${
              status?.enabled
                ? 'border border-destructive/50 bg-destructive/10 text-destructive hover:bg-destructive/20'
                : 'bg-primary text-primary-foreground hover:opacity-90'
            }`}
          >
            {busy ? 'Working…' : status?.enabled ? 'Disable' : 'Enable'}
          </button>
        </div>
      </header>

      {error && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
          {error}
        </div>
      )}

      {loading && !status && <p className="text-sm text-muted-foreground">Loading connection status…</p>}

      {status && (
        <>
          <Section title="Status">
            <p className="text-sm">{status.detail}</p>
            {status.lastError && (
              <p className="mt-2 break-words rounded border border-destructive/30 bg-destructive/5 p-2 font-mono text-xs text-destructive">
                {status.lastError}
              </p>
            )}
            <div className="mt-3 grid gap-x-6 gap-y-1 text-xs text-muted-foreground sm:grid-cols-2">
              <span>Last connected: {formatTimestamp(status.lastConnectedAt)}</span>
              <span>Last disconnected: {formatTimestamp(status.lastDisconnectedAt)}</span>
            </div>
            <p className="mt-2 text-xs text-muted-foreground">
              The on/off switch above is stored in the database, so it survives a restart of this
              instance.
            </p>
          </Section>

          <Section title="Parameters">
            <ParameterRow label="Control plane" value={status.parameters.controlUri || '—'} />
            <ParameterRow label="Client id" value={status.parameters.clientId || '— (not set)'} />
            <ParameterRow
              label="Shared secret"
              value={status.parameters.secretConfigured ? secretShown || status.parameters.secret : '— (not set)'}
              hint={
                status.parameters.secretConfigured
                  ? secretShown
                    ? 'Revealed. Treat this as a credential — it authenticates this instance to Namazu Cloud.'
                    : 'Masked. Click Reveal to show the value in plaintext.'
                  : undefined
              }
            />
            {status.parameters.secretConfigured && (
              <div className="pt-3">
                <button
                  onClick={toggleSecret}
                  disabled={revealBusy}
                  className="rounded-md border border-border bg-secondary px-3 py-1.5 text-xs font-medium text-secondary-foreground transition-colors hover:opacity-90 disabled:opacity-50"
                >
                  {revealBusy ? 'Revealing…' : secretShown ? 'Hide secret' : 'Reveal secret'}
                </button>
              </div>
            )}
          </Section>

          <Section title="Configuration sources">
            <p className="mb-2 text-xs text-muted-foreground">
              Values resolved at start-up. An environment variable always wins over the element
              attribute of the same name.
            </p>
            {status.parameters.values.map((p) => (
              <ParameterRow
                key={p.key}
                label={p.key}
                value={p.value || '— (blank)'}
                hint={p.overriddenByEnvironment ? `overridden by environment ${p.envKey}` : `default ${p.envKey}`}
              />
            ))}
            <div className="mt-3 grid gap-x-6 gap-y-1 text-xs text-muted-foreground sm:grid-cols-2">
              <span>Reconnect delay: {status.parameters.retrySeconds}s</span>
              <span>Session lifetime: {status.parameters.sessionTtlMinutes} minutes</span>
            </div>
          </Section>
        </>
      )}
    </div>
  )
}
