// TCP client for the in-mod CoopAgentBridge (-Dcoop.debug.bridge=<port>).
//
// Wire protocol: newline-delimited JSON over a single localhost TCP connection.
//   request  {"id":<int>,"cmd":"<verb>","args":{...}}
//   response {"id":<int>,"ok":true,"data":{...}}
//            {"id":<int>,"ok":false,"error":"<Class>: <message>"}
//
// The bridge services a few commands per game frame, so a response can lag a frame
// or two behind the write and can arrive out of order relative to other requests.
// Every request therefore carries a unique id and its own timeout, and the client
// reconnects once if the socket drops while a request is in flight.

import net from 'node:net';

export const DEFAULT_TIMEOUT_MS = 10_000;

/** The bridge answered with ok:false. `error` carries the mod's error string verbatim. */
export class BridgeError extends Error {
  constructor(instance, cmd, bridgeError) {
    super(`bridge "${instance}" refused ${cmd}: ${bridgeError}`);
    this.name = 'BridgeError';
    this.instance = instance;
    this.cmd = cmd;
    this.bridgeError = bridgeError;
  }
}

/** No socket at 127.0.0.1:<port> — the instance is not running, or was launched without -Bridge. */
export class BridgeUnreachableError extends Error {
  constructor(message, instance, port) {
    super(message);
    this.name = 'BridgeUnreachableError';
    this.instance = instance;
    this.port = port;
  }
}

/** No response within the per-request timeout. */
export class BridgeTimeoutError extends Error {
  constructor(instance, cmd, timeoutMs) {
    super(`bridge "${instance}" did not answer ${cmd} within ${timeoutMs} ms`);
    this.name = 'BridgeTimeoutError';
    this.instance = instance;
    this.cmd = cmd;
  }
}

/** The connection went away mid-request. Internal: triggers exactly one reconnect+retry. */
class BridgeDroppedError extends Error {
  constructor(instance, reason) {
    super(`bridge "${instance}" connection dropped: ${reason}`);
    this.name = 'BridgeDroppedError';
  }
}

export class BridgeClient {
  /**
   * @param {object} opts
   * @param {string} opts.instance     instance name used in every error message ("host" / "guest")
   * @param {string} [opts.host]       bind address, always localhost in normal use
   * @param {number} opts.port
   * @param {number} [opts.timeoutMs]
   * @param {string} [opts.hint]       appended to the unreachable message (launch switch, env var)
   */
  constructor({ instance, host = '127.0.0.1', port, timeoutMs = DEFAULT_TIMEOUT_MS, hint = '' }) {
    this.instance = instance;
    this.host = host;
    this.port = port;
    this.timeoutMs = timeoutMs;
    this.hint = hint;

    this._socket = null;
    this._connecting = null;
    this._pending = new Map();
    this._nextId = 1;
    this._buffer = '';
  }

  /** Send one command and resolve with its `data` object. Retries once on a dropped socket. */
  async send(cmd, args) {
    try {
      return await this._request(cmd, args);
    } catch (err) {
      if (!(err instanceof BridgeDroppedError)) throw err;
      this._reset(`retrying after: ${err.message}`);
      return await this._request(cmd, args);
    }
  }

  close() {
    this._reset('closed by client');
  }

  async _request(cmd, args) {
    const socket = await this._connect();
    const id = this._nextId++;
    const line = `${JSON.stringify({ id, cmd, args: args ?? {} })}\n`;

    const answer = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(id);
        reject(new BridgeTimeoutError(this.instance, cmd, this.timeoutMs));
      }, this.timeoutMs);
      if (typeof timer.unref === 'function') timer.unref();
      this._pending.set(id, { cmd, resolve, reject, timer });
    });

    try {
      socket.write(line);
    } catch (err) {
      this._settle(id, (entry) => entry.reject(new BridgeDroppedError(this.instance, err.message)));
    }
    return answer;
  }

  _connect() {
    if (this._socket && !this._socket.destroyed) return Promise.resolve(this._socket);
    if (this._connecting) return this._connecting;

    this._connecting = new Promise((resolve, reject) => {
      const socket = net.createConnection({ host: this.host, port: this.port });
      socket.setNoDelay(true);
      socket.setEncoding('utf8');

      const onConnectError = (err) => {
        socket.destroy();
        this._connecting = null;
        reject(this._unreachable(err));
      };

      socket.once('error', onConnectError);
      socket.once('connect', () => {
        socket.removeListener('error', onConnectError);
        socket.on('error', (err) => this._onDrop(socket, err.message));
        socket.on('close', () => this._onDrop(socket, 'closed by the game'));
        socket.on('data', (chunk) => this._onData(chunk));
        this._socket = socket;
        this._connecting = null;
        resolve(socket);
      });
    });
    return this._connecting;
  }

  _unreachable(err) {
    const code = err.code ? `${err.code}` : err.message;
    return new BridgeUnreachableError(
      `Starsector bridge "${this.instance}" is not reachable at ${this.host}:${this.port} (${code}). ` +
        `The instance is not running, or it was launched without the bridge. ${this.hint}`.trim(),
      this.instance,
      this.port
    );
  }

  _onDrop(socket, reason) {
    if (this._socket !== socket) return;
    this._reset(reason);
  }

  _reset(reason) {
    const socket = this._socket;
    this._socket = null;
    this._buffer = '';
    if (socket) {
      socket.removeAllListeners();
      socket.destroy();
    }
    const pending = [...this._pending.values()];
    this._pending.clear();
    for (const entry of pending) {
      clearTimeout(entry.timer);
      entry.reject(new BridgeDroppedError(this.instance, reason));
    }
  }

  _onData(chunk) {
    this._buffer += chunk;
    let newline = this._buffer.indexOf('\n');
    while (newline >= 0) {
      const line = this._buffer.slice(0, newline).trim();
      this._buffer = this._buffer.slice(newline + 1);
      if (line) this._onLine(line);
      newline = this._buffer.indexOf('\n');
    }
  }

  _onLine(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      // Not correlatable to a request; the bridge is the only writer, so say so and keep going.
      process.stderr.write(`[starsector-mcp] ${this.instance}: unparsable bridge line: ${line}\n`);
      return;
    }
    // A response whose id is gone belongs to a request that already timed out. Drop it.
    this._settle(message.id, (entry) => {
      if (message.ok) entry.resolve(message.data ?? {});
      else entry.reject(new BridgeError(this.instance, entry.cmd, message.error ?? 'ok:false with no error field'));
    });
  }

  _settle(id, apply) {
    const entry = this._pending.get(id);
    if (!entry) return;
    this._pending.delete(id);
    clearTimeout(entry.timer);
    apply(entry);
  }
}
