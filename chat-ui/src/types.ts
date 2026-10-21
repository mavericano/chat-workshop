export interface User {
  id: number;
  email: string;
  fullName: string;
}

export interface Session {
  accessToken: string;
  expiresAt: string;
  user: User;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
}

export type McpTransport = 'STREAMABLE_HTTP' | 'SSE';

export interface McpTool {
  name: string;
  description: string | null;
}

export interface McpServer {
  id: number;
  name: string;
  url: string;
  transport: McpTransport;
  enabled: boolean;
  headerName: string | null;
  headerValueMasked: string | null;
  status: 'CONNECTED' | 'ERROR' | 'DISCONNECTED' | 'DISABLED';
  error: string | null;
  serverInfo: string | null;
  tools: McpTool[];
  createdAt: string;
}

export interface McpServerRequest {
  name: string;
  url: string;
  transport: McpTransport;
  headerName: string | null;
  headerValue: string | null;
}

export interface McpTestResult {
  success: boolean;
  serverInfo: string | null;
  tools: McpTool[];
  error: string | null;
}
