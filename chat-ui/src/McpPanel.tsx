import { useEffect, useState, type FormEvent } from 'react';
import { api } from './api';
import type { McpServer, McpServerRequest, McpTestResult, McpTool, McpTransport } from './types';

interface Props {
  open: boolean;
  onClose: () => void;
  onChange: (servers: McpServer[]) => void;
}

const STATUS_LABEL: Record<McpServer['status'], string> = {
  CONNECTED: 'connected',
  ERROR: 'error',
  DISCONNECTED: 'not connected yet',
  DISABLED: 'disabled',
};

function errorText(e: unknown) {
  return e instanceof Error ? e.message : String(e);
}

export default function McpPanel({ open, onClose, onChange }: Props) {
  const [servers, setServers] = useState<McpServer[]>([]);
  const [adding, setAdding] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const update = (next: McpServer[]) => {
    setServers(next);
    onChange(next);
  };

  const reload = () =>
    api
      .getMcpServers()
      .then(update)
      .catch((e) => setError(errorText(e)));

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!open) return;
    void reload();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const run = async (id: number, action: () => Promise<McpServer | void>) => {
    setBusyId(id);
    setError(null);
    try {
      const result = await action();
      update(result ? servers.map((s) => (s.id === id ? result : s)) : servers.filter((s) => s.id !== id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusyId(null);
    }
  };

  const remove = (server: McpServer) => {
    if (window.confirm(`Remove MCP server "${server.name}"? Its tools will no longer be available in the chat.`)) {
      void run(server.id, () => api.deleteMcpServer(server.id));
    }
  };

  return (
    <aside className={`mcp-panel${open ? ' open' : ''}`} aria-hidden={!open} aria-label="MCP servers">
      <header className="mcp-header">
        <div>
          <strong>MCP servers</strong>
          <small>Tools from these servers are available to the assistant</small>
        </div>
        <button type="button" className="icon-button" onClick={onClose} aria-label="Close">
          ×
        </button>
      </header>

      <div className="mcp-body">
        {error && <div className="error">{error}</div>}

        {servers.length === 0 && !adding && (
          <p className="muted">No servers yet. Add an MCP server to give the assistant new tools.</p>
        )}

        {servers.map((server) => (
          <ServerCard
            key={server.id}
            server={server}
            busy={busyId === server.id}
            onToggle={() => run(server.id, () => api.setMcpServerEnabled(server.id, !server.enabled))}
            onReconnect={() => run(server.id, () => api.reconnectMcpServer(server.id))}
            onRemove={() => remove(server)}
          />
        ))}

        {adding ? (
          <AddServerForm
            onCancel={() => setAdding(false)}
            onAdded={(server) => {
              update([...servers, server].sort((a, b) => a.name.localeCompare(b.name)));
              setAdding(false);
            }}
          />
        ) : (
          <button type="button" className="primary block" onClick={() => setAdding(true)}>
            + Add server
          </button>
        )}
      </div>
    </aside>
  );
}

function ServerCard({
  server,
  busy,
  onToggle,
  onReconnect,
  onRemove,
}: {
  server: McpServer;
  busy: boolean;
  onToggle: () => void;
  onReconnect: () => void;
  onRemove: () => void;
}) {
  const [showTools, setShowTools] = useState(false);

  return (
    <div className={`mcp-card status-${server.status.toLowerCase()}`}>
      <div className="mcp-card-head">
        <span className="dot" title={STATUS_LABEL[server.status]} />
        <div className="mcp-card-title">
          <strong>{server.name}</strong>
          <small title={server.url}>{server.url}</small>
        </div>
        <label className="switch" title={server.enabled ? 'Disable' : 'Enable'}>
          <input type="checkbox" checked={server.enabled} onChange={onToggle} disabled={busy} />
          <span />
        </label>
      </div>

      <div className="mcp-card-meta">
        <span>
          {busy ? 'working…' : STATUS_LABEL[server.status]}
          {server.status === 'CONNECTED' && ` · ${server.tools.length} tools`}
          {server.serverInfo && ` · ${server.serverInfo}`}
          {server.transport === 'SSE' && ' · SSE'}
          {server.headerName && ` · ${server.headerName}: ${server.headerValueMasked}`}
        </span>
      </div>

      {server.error && <div className="mcp-error">⚠ {server.error}</div>}

      <div className="mcp-card-actions">
        {server.tools.length > 0 && (
          <button type="button" onClick={() => setShowTools((v) => !v)}>
            {showTools ? 'Hide tools' : 'Show tools'}
          </button>
        )}
        {server.enabled && (
          <button type="button" onClick={onReconnect} disabled={busy}>
            ↻ Reconnect
          </button>
        )}
        <span className="spacer" />
        <button type="button" className="danger" onClick={onRemove} disabled={busy}>
          Remove
        </button>
      </div>

      {showTools && <ToolList tools={server.tools} />}
    </div>
  );
}

function ToolList({ tools }: { tools: McpTool[] }) {
  return (
    <ul className="mcp-tools">
      {tools.map((tool) => (
        <li key={tool.name}>
          <code>{tool.name}</code>
          {tool.description && <small>{tool.description}</small>}
        </li>
      ))}
    </ul>
  );
}

function AddServerForm({ onCancel, onAdded }: { onCancel: () => void; onAdded: (server: McpServer) => void }) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [transport, setTransport] = useState<McpTransport>('STREAMABLE_HTTP');
  const [headerName, setHeaderName] = useState('Authorization');
  const [headerValue, setHeaderValue] = useState('');
  const [test, setTest] = useState<McpTestResult | null>(null);
  const [busy, setBusy] = useState<'test' | 'save' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const body = (): McpServerRequest => ({
    name: name.trim(),
    url: url.trim(),
    transport,
    headerName: headerValue.trim() ? headerName.trim() : null,
    headerValue: headerValue.trim() || null,
  });

  // any change invalidates a previous test result
  const changed = <T,>(setter: (v: T) => void) => (v: T) => {
    setter(v);
    setTest(null);
  };

  const runTest = async () => {
    setBusy('test');
    setError(null);
    try {
      setTest(await api.testMcpServer(body()));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(null);
    }
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy('save');
    setError(null);
    try {
      onAdded(await api.addMcpServer(body()));
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <form className="mcp-form" onSubmit={save}>
      <h3>Add MCP server</h3>
      <label>
        Name
        <input
          value={name}
          onChange={(e) => changed(setName)(e.target.value)}
          placeholder="github"
          pattern="[A-Za-z0-9_\-]{1,40}"
          title="1-40 characters: letters, digits, '-' or '_'"
          required
          autoFocus
        />
        <small className="hint">Used as prefix of the tool names, e.g. github__search_code</small>
      </label>
      <label>
        URL
        <input
          type="url"
          value={url}
          onChange={(e) => changed(setUrl)(e.target.value)}
          placeholder="https://example.com/mcp"
          required
        />
        <small className="hint">Public https endpoint. Without a path, /mcp (or /sse) is used.</small>
      </label>
      <label>
        Transport
        <select value={transport} onChange={(e) => changed(setTransport)(e.target.value as McpTransport)}>
          <option value="STREAMABLE_HTTP">Streamable HTTP (recommended)</option>
          <option value="SSE">SSE (legacy)</option>
        </select>
      </label>
      <div className="row">
        <label>
          Auth header
          <input value={headerName} onChange={(e) => changed(setHeaderName)(e.target.value)} placeholder="Authorization" />
        </label>
        <label>
          Value (optional)
          <input
            type="password"
            value={headerValue}
            onChange={(e) => changed(setHeaderValue)(e.target.value)}
            placeholder="Bearer …"
            autoComplete="off"
          />
        </label>
      </div>
      <small className="hint">The header value is stored encrypted and never shown again.</small>

      {test &&
        (test.success ? (
          <div className="info">
            ✓ Connected{test.serverInfo ? ` to ${test.serverInfo}` : ''}, {test.tools.length} tools
            <ToolList tools={test.tools} />
          </div>
        ) : (
          <div className="error">Connection failed: {test.error}</div>
        ))}
      {error && <div className="error">{error}</div>}

      <div className="mcp-form-actions">
        <button type="button" onClick={onCancel} disabled={busy !== null}>
          Cancel
        </button>
        <span className="spacer" />
        <button type="button" onClick={runTest} disabled={busy !== null || !name.trim() || !url.trim()}>
          {busy === 'test' ? 'Testing…' : 'Test connection'}
        </button>
        <button type="submit" className="primary" disabled={busy !== null}>
          {busy === 'save' ? 'Saving…' : 'Save'}
        </button>
      </div>
    </form>
  );
}
