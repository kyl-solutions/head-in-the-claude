import 'dotenv/config';
import { serve } from '@hono/node-server';
import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { authMiddleware } from './lib/auth.js';
import { SessionManager } from './lib/session-manager.js';
import { ClaudeBridge } from './lib/claude-bridge.js';

const app = new Hono();
const sessionManager = new SessionManager();
const claudeBridge = new ClaudeBridge(process.env.CLAUDE_PATH || '/Users/kylsolutions/.local/bin/claude');

const PORT = parseInt(process.env.PORT || '3847', 10);
const WORKING_DIR = process.env.WORKING_DIR || '/Users/kylsolutions/Developer/kyl-solutions';

// ---------------------------------------------------------------------------
// Middleware
// ---------------------------------------------------------------------------
app.use('*', cors());

// ---------------------------------------------------------------------------
// Health check (no auth)
// ---------------------------------------------------------------------------
app.get('/api/health', (c) => {
  return c.json({
    status: 'ok',
    version: '0.1.0',
    uptime: Math.floor(process.uptime()),
    activeSessions: sessionManager.list().length,
  });
});

// ---------------------------------------------------------------------------
// Chat — send message to Claude, stream response via SSE
// ---------------------------------------------------------------------------
app.post('/api/chat', authMiddleware, async (c) => {
  const { sessionId, message, image, workingDir } = await c.req.json();

  if (!message && !image) {
    return c.json({ error: 'message is required' }, 400);
  }

  // Resolve or create session
  const session = sessionManager.getOrCreate(sessionId);
  const isFirstMessage = session.isNew || session.messageCount === 0;

  // Reject if session already has an active process
  if (claudeBridge.isActive(session.id)) {
    return c.json({ error: 'Session has an active request. Abort it first or wait.' }, 409);
  }

  sessionManager.recordMessage(session.id);

  // Stream response as SSE
  const stream = new ReadableStream({
    start(controller) {
      const encoder = new TextEncoder();

      const sendEvent = (type, data) => {
        try {
          controller.enqueue(encoder.encode(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`));
        } catch (e) {
          // Stream might be closed
        }
      };

      // Send session info first
      sendEvent('session', { sessionId: session.id, isNew: session.isNew });

      // Spawn Claude CLI
      const child = claudeBridge.sendMessage({
        sessionId: session.id,
        message: message || 'Analyze this image.',
        image: image || null,
        workingDir: workingDir || WORKING_DIR,
        isFirstMessage,
        onData: (chunk) => {
          sendEvent('chunk', chunk);
        },
        onToolUse: (tool) => {
          sendEvent('tool', { name: tool.name, id: tool.id });
        },
        onComplete: (result) => {
          sessionManager.clearActiveProcess(session.id);
          sendEvent('done', { sessionId: session.id });
          try { controller.close(); } catch (e) { /* already closed */ }
        },
        onError: (err) => {
          sessionManager.clearActiveProcess(session.id);
          sendEvent('error', { message: err.message });
          try { controller.close(); } catch (e) { /* already closed */ }
        },
      });

      sessionManager.setActiveProcess(session.id, child);
    },
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    },
  });
});

// ---------------------------------------------------------------------------
// Abort — kill active Claude process for a session
// ---------------------------------------------------------------------------
app.post('/api/chat/abort', authMiddleware, async (c) => {
  const { sessionId } = await c.req.json();
  if (!sessionId) {
    return c.json({ error: 'sessionId required' }, 400);
  }

  const aborted = claudeBridge.abort(sessionId);
  sessionManager.clearActiveProcess(sessionId);
  return c.json({ aborted });
});

// ---------------------------------------------------------------------------
// Sessions — list, create, delete
// ---------------------------------------------------------------------------
app.get('/api/sessions', authMiddleware, (c) => {
  return c.json({ sessions: sessionManager.list() });
});

app.post('/api/sessions', authMiddleware, (c) => {
  const session = sessionManager.getOrCreate();
  return c.json({ sessionId: session.id });
});

app.delete('/api/sessions/:id', authMiddleware, (c) => {
  const id = c.req.param('id');
  const deleted = sessionManager.delete(id);
  return c.json({ deleted });
});

// ---------------------------------------------------------------------------
// Start server
// ---------------------------------------------------------------------------
serve({ fetch: app.fetch, port: PORT }, (info) => {
  console.log(`
╔══════════════════════════════════════════════╗
║   HITC Relay Server v0.1.0                   ║
║   http://0.0.0.0:${String(info.port).padEnd(27)}║
║   Claude: ${(process.env.CLAUDE_PATH || 'default').slice(-34).padEnd(34)}║
║   CWD:    ${WORKING_DIR.slice(-34).padEnd(34)}║
╚══════════════════════════════════════════════╝
  `);
});
