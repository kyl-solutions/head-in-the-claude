import { spawn } from 'child_process';
import { createInterface } from 'readline';

/**
 * Bridges HTTP requests to Claude Code CLI child processes.
 * Spawns `claude --print --output-format stream-json` and streams events back.
 */
export class ClaudeBridge {
  constructor(claudePath) {
    this.claudePath = claudePath;
    this.activeProcesses = new Map(); // sessionId -> ChildProcess
  }

  /**
   * Send a message to Claude, returning events via callbacks.
   *
   * @param {Object} opts
   * @param {string} opts.sessionId - Conversation UUID
   * @param {string} opts.message - User message text
   * @param {string|null} opts.image - Base64 JPEG image or null
   * @param {string} opts.workingDir - CWD for Claude process
   * @param {boolean} opts.isFirstMessage - true = --session-id, false = --resume
   * @param {Function} opts.onData - Called with text chunks
   * @param {Function} opts.onToolUse - Called when Claude uses a tool
   * @param {Function} opts.onComplete - Called when process finishes successfully
   * @param {Function} opts.onError - Called on error
   * @returns {ChildProcess}
   */
  sendMessage({ sessionId, message, image, workingDir, isFirstMessage, onData, onToolUse, onComplete, onError }) {
    const args = this.buildArgs(sessionId, message, image, isFirstMessage);

    const child = spawn(this.claudePath, args, {
      cwd: workingDir,
      env: {
        ...process.env,
        // Inherit all env vars including Claude auth
      },
      stdio: image ? ['pipe', 'pipe', 'pipe'] : ['ignore', 'pipe', 'pipe'],
    });

    this.activeProcesses.set(sessionId, child);

    // If image provided, pipe it via stream-json stdin
    if (image) {
      this.writeImageInput(child, message, image);
    }

    // Read stdout line by line (each line is a JSON event)
    const rl = createInterface({ input: child.stdout });
    let resultData = null;

    rl.on('line', (line) => {
      const trimmed = line.trim();
      if (!trimmed) return;

      try {
        const event = JSON.parse(trimmed);
        this.handleEvent(event, { onData, onToolUse });

        // Capture final result
        if (event.type === 'result') {
          resultData = event;
        }
      } catch (e) {
        // Non-JSON line — forward as raw text
        if (trimmed.length > 0) {
          onData({ type: 'text', text: trimmed + '\n' });
        }
      }
    });

    // Collect stderr for error reporting
    let stderr = '';
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString();
    });

    child.on('close', (code) => {
      this.activeProcesses.delete(sessionId);
      if (code === 0) {
        onComplete({ sessionId, exitCode: code, result: resultData });
      } else {
        onError(new Error(`Claude exited with code ${code}${stderr ? ': ' + stderr.trim() : ''}`));
      }
    });

    child.on('error', (err) => {
      this.activeProcesses.delete(sessionId);
      onError(err);
    });

    return child;
  }

  /**
   * Build CLI arguments for the claude command.
   */
  buildArgs(sessionId, message, image, isFirstMessage) {
    const args = [
      '--print',
      '--output-format', 'stream-json',
      '--verbose',
      '--dangerously-skip-permissions',
    ];

    if (isFirstMessage) {
      args.push('--session-id', sessionId);
    } else {
      args.push('--resume', sessionId);
    }

    if (image) {
      // When sending an image, use stdin with stream-json input
      args.push('--input-format', 'stream-json');
    } else {
      // Text-only: pass message as positional argument
      args.push(message);
    }

    return args;
  }

  /**
   * Write an image message to Claude's stdin in stream-json format.
   */
  writeImageInput(child, message, imageBase64) {
    const inputMessage = {
      type: 'user',
      message: {
        role: 'user',
        content: [
          { type: 'text', text: message },
          {
            type: 'image',
            source: {
              type: 'base64',
              media_type: 'image/jpeg',
              data: imageBase64,
            },
          },
        ],
      },
    };

    try {
      child.stdin.write(JSON.stringify(inputMessage) + '\n');
      child.stdin.end();
    } catch (e) {
      // stdin might already be closed
    }
  }

  /**
   * Parse a stream-json event and dispatch to the appropriate callback.
   */
  handleEvent(event, { onData, onToolUse }) {
    switch (event.type) {
      case 'assistant':
        // Full assistant message — primary source of text and tool_use blocks.
        // Claude CLI stream-json outputs complete messages (not incremental deltas).
        if (event.message?.content) {
          for (const block of event.message.content) {
            if (block.type === 'text') {
              onData({ type: 'text', text: block.text });
            } else if (block.type === 'tool_use') {
              onToolUse({ name: block.name, id: block.id, input: block.input });
            }
          }
        }
        break;

      case 'content_block_delta':
        // Incremental streaming deltas (if available in future CLI versions)
        if (event.delta?.type === 'text_delta' && event.delta?.text) {
          onData({ type: 'text', text: event.delta.text });
        }
        break;

      case 'tool_use':
        onToolUse({ name: event.name, id: event.id, input: event.input });
        break;

      case 'tool_result':
        onData({
          type: 'tool_result',
          toolUseId: event.tool_use_id,
          content: typeof event.content === 'string'
            ? event.content.slice(0, 500) // Truncate large tool results for the phone display
            : JSON.stringify(event.content).slice(0, 500),
        });
        break;

      case 'result':
        // Final result — text here is the same as assistant message, skip to avoid duplication.
        // Metadata (cost, duration) is captured in the close handler.
        break;

      case 'system':
        // Init event — session info, tools list. No user-visible action needed.
        break;

      default:
        // Unknown event type — ignore
        break;
    }
  }

  /**
   * Abort a running Claude process.
   */
  abort(sessionId) {
    const child = this.activeProcesses.get(sessionId);
    if (child) {
      child.kill('SIGTERM');
      this.activeProcesses.delete(sessionId);
      return true;
    }
    return false;
  }

  /**
   * Check if a session has an active process.
   */
  isActive(sessionId) {
    return this.activeProcesses.has(sessionId);
  }
}
