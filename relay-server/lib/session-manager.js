import { v4 as uuidv4 } from 'uuid';

/**
 * In-memory session manager for Claude conversations.
 * Each session maps to a Claude CLI --session-id / --resume UUID.
 */
export class SessionManager {
  constructor() {
    this.sessions = new Map();
  }

  getOrCreate(sessionId) {
    if (sessionId && this.sessions.has(sessionId)) {
      const session = this.sessions.get(sessionId);
      return { ...session, isNew: false };
    }

    const id = sessionId || uuidv4();
    const session = {
      id,
      createdAt: new Date().toISOString(),
      lastMessageAt: null,
      messageCount: 0,
      activeProcess: null,
      workingDir: null,
    };
    this.sessions.set(id, session);
    return { ...session, isNew: true };
  }

  get(sessionId) {
    return this.sessions.get(sessionId) || null;
  }

  recordMessage(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.messageCount++;
      session.lastMessageAt = new Date().toISOString();
    }
  }

  setActiveProcess(sessionId, process) {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.activeProcess = process;
    }
  }

  clearActiveProcess(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.activeProcess = null;
    }
  }

  list() {
    return Array.from(this.sessions.values()).map(s => ({
      id: s.id,
      createdAt: s.createdAt,
      lastMessageAt: s.lastMessageAt,
      messageCount: s.messageCount,
      isActive: s.activeProcess !== null,
    }));
  }

  delete(sessionId) {
    const session = this.sessions.get(sessionId);
    if (session?.activeProcess) {
      try { session.activeProcess.kill('SIGTERM'); } catch (e) { /* ignore */ }
    }
    return this.sessions.delete(sessionId);
  }

  clearAll() {
    let count = 0;
    for (const [id, session] of this.sessions) {
      if (session.activeProcess) {
        try { session.activeProcess.kill('SIGTERM'); } catch (e) { /* ignore */ }
      }
      count++;
    }
    this.sessions.clear();
    return count;
  }
}
