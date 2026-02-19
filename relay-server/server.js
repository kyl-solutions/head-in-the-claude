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

  // Stream response as SSE with heartbeat keepalive
  let streamChild = null;
  let heartbeatTimer = null;
  let processTimer = null;
  let streamClosed = false;

  const HEARTBEAT_MS = 15_000;    // ping every 15s to keep connection alive
  const PROCESS_TIMEOUT_MS = 600_000; // kill hung Claude after 10 minutes

  const stream = new ReadableStream({
    start(controller) {
      const encoder = new TextEncoder();

      const sendEvent = (type, data) => {
        if (streamClosed) return;
        try {
          controller.enqueue(encoder.encode(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`));
        } catch (e) {
          // Stream might be closed
        }
      };

      const sendHeartbeat = () => {
        if (streamClosed) return;
        try {
          // SSE comment line — keeps connection alive, clients ignore it
          controller.enqueue(encoder.encode(': heartbeat\n\n'));
        } catch (e) {
          // Stream closed, stop heartbeat
          clearTimers();
        }
      };

      const clearTimers = () => {
        if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
        if (processTimer) { clearTimeout(processTimer); processTimer = null; }
      };

      const closeStream = () => {
        if (streamClosed) return;
        streamClosed = true;
        clearTimers();
        try { controller.close(); } catch (e) { /* already closed */ }
      };

      // Start heartbeat — fires every 15s to prevent network timeouts
      heartbeatTimer = setInterval(sendHeartbeat, HEARTBEAT_MS);

      // Process timeout — kill Claude if it hangs beyond 10 minutes
      processTimer = setTimeout(() => {
        console.warn(`[relay] Session ${session.id}: Claude process timed out after ${PROCESS_TIMEOUT_MS / 1000}s`);
        claudeBridge.abort(session.id);
        sessionManager.clearActiveProcess(session.id);
        sendEvent('error', { message: 'Request timed out (10 min limit)' });
        closeStream();
      }, PROCESS_TIMEOUT_MS);

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
          closeStream();
        },
        onError: (err) => {
          sessionManager.clearActiveProcess(session.id);
          sendEvent('error', { message: err.message });
          closeStream();
        },
      });

      streamChild = child;
      sessionManager.setActiveProcess(session.id, child);
    },
    cancel() {
      // Called when client disconnects — kill orphaned process & clear state
      console.log(`[relay] Session ${session.id}: Client disconnected, cleaning up`);
      streamClosed = true;
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
      if (processTimer) { clearTimeout(processTimer); processTimer = null; }
      if (streamChild) {
        try { streamChild.kill('SIGTERM'); } catch (e) { /* ignore */ }
      }
      claudeBridge.abort(session.id);
      sessionManager.clearActiveProcess(session.id);
    },
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',  // Disable proxy buffering (nginx, Tailscale)
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
// Projects — scan working dir for dev projects
// ---------------------------------------------------------------------------
app.get('/api/projects', authMiddleware, async (c) => {
  const fs = await import('node:fs/promises');
  const path = await import('node:path');
  const { execSync } = await import('node:child_process');

  const projectMarkers = ['.git', 'package.json', 'build.gradle.kts', 'build.gradle', 'Cargo.toml', 'go.mod', 'pom.xml', 'requirements.txt', 'pyproject.toml', 'Makefile'];

  try {
    const entries = await fs.readdir(WORKING_DIR, { withFileTypes: true });
    const projects = [];

    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith('.')) continue;

      const dirPath = path.join(WORKING_DIR, entry.name);
      let type = null;

      for (const marker of projectMarkers) {
        try {
          await fs.access(path.join(dirPath, marker));
          type = marker.replace('.', '').replace('_', '-');
          break;
        } catch { /* marker not found */ }
      }

      if (type) {
        // Get filesystem modification time (captures any file change)
        let lastModified = 0;
        try {
          const stat = await fs.stat(dirPath);
          lastModified = Math.floor(stat.mtimeMs / 1000);
        } catch { /* stat failed */ }

        let lastCommit = null;
        // Read latest git commit info if .git exists
        try {
          await fs.access(path.join(dirPath, '.git'));
          const ts = parseInt(execSync('git log -1 --format=%ct', { cwd: dirPath, timeout: 3000 }).toString().trim(), 10);
          const msg = execSync('git log -1 --format=%s', { cwd: dirPath, timeout: 3000 }).toString().trim();
          if (!isNaN(ts)) lastCommit = { timestamp: ts, message: msg };
        } catch { /* no git history or not a git repo */ }

        projects.push({ name: entry.name, path: dirPath, type, lastModified, lastCommit });
      }
    }

    // Sort by filesystem modification time (most recently modified first)
    projects.sort((a, b) => b.lastModified - a.lastModified);
    return c.json({ projects });
  } catch (e) {
    return c.json({ error: e.message, projects: [] }, 500);
  }
});

// ---------------------------------------------------------------------------
// Project context command — read .claude/commands/<name>.md from a project
// ---------------------------------------------------------------------------
app.get('/api/projects/:name/context', authMiddleware, async (c) => {
  const fs = await import('node:fs/promises');
  const path = await import('node:path');

  const projectName = c.req.param('name');
  const projectDir = path.join(WORKING_DIR, projectName);

  try {
    // Look for the project's own context command
    const commandsDir = path.join(projectDir, '.claude', 'commands');
    const entries = await fs.readdir(commandsDir).catch(() => []);
    const mdFiles = entries.filter(f => f.endsWith('.md'));

    if (mdFiles.length === 0) {
      return c.json({ found: false, name: projectName, content: null });
    }

    // Return the first .md command file content (sent as prompt to Claude)
    const commandFile = mdFiles[0];
    const content = await fs.readFile(path.join(commandsDir, commandFile), 'utf-8');
    return c.json({
      found: true,
      name: projectName,
      command: commandFile.replace('.md', ''),
      content,
      path: path.join(WORKING_DIR, projectName),
    });
  } catch (e) {
    return c.json({ found: false, name: projectName, error: e.message });
  }
});

// ---------------------------------------------------------------------------
// Sessions — list, create, delete, clear all
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

// Clear all relay sessions
app.delete('/api/sessions', authMiddleware, (c) => {
  const cleared = sessionManager.clearAll();
  return c.json({ cleared });
});

// Reset — force-clear stuck session state (fixes 409 errors)
app.post('/api/sessions/:id/reset', authMiddleware, (c) => {
  const id = c.req.param('id');
  claudeBridge.abort(id);
  sessionManager.clearActiveProcess(id);
  return c.json({ reset: true, sessionId: id });
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
