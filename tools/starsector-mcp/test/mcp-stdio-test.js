// Exercise the real SDK client/server boundary without opening a game bridge socket.
import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('SDK stdio initializes the server, lists its public tools and closes the child',
  { timeout: 15_000 }, async (t) => {
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [fileURLToPath(new URL('../index.js', import.meta.url))],
      cwd: fileURLToPath(new URL('..', import.meta.url)),
      stderr: 'pipe'
    });
    const client = new Client({ name: 'starsector-mcp-stdio-test', version: '1.0.0' },
      { capabilities: {} });
    let stderr = '';
    transport.stderr.on('data', (chunk) => { stderr = (stderr + chunk.toString()).slice(-8_192); });
    t.after(async () => { await client.close(); });

    try {
      // connect performs initialize/initialized over the spawned server's actual stdin/stdout.
      await client.connect(transport);
      const childPid = transport.pid;
      assert.equal(typeof childPid, 'number');
      assert.deepEqual(client.getServerVersion(), { name: 'starsector-mcp', version: '1.0.0' });
      assert.deepEqual(client.getServerCapabilities().tools, {});

      const { tools } = await client.listTools();
      assert.deepEqual(tools.map((tool) => tool.name).sort(), [
        'ss_act', 'ss_advance_days', 'ss_align_clocks', 'ss_diff', 'ss_dump', 'ss_status'
      ]);
      for (const tool of tools) {
        assert.equal(typeof tool.description, 'string');
        assert.ok(tool.description.length > 0, `${tool.name} must describe its operation`);
        assert.equal(tool.inputSchema.type, 'object');
        for (const required of tool.inputSchema.required ?? []) {
          assert.ok(Object.hasOwn(tool.inputSchema.properties, required),
            `${tool.name} must define its required argument ${required}`);
        }
      }
      const status = tools.find((tool) => tool.name === 'ss_status');
      assert.deepEqual(status.inputSchema.required, ['instance']);
      assert.deepEqual(status.inputSchema.properties.instance.enum, ['host', 'guest']);

      // This dispatch failure stays inside MCP; no live Starsector or mock TCP bridge is needed.
      const invalid = await client.callTool({ name: 'does_not_exist', arguments: {} });
      assert.equal(invalid.isError, true);
      assert.equal(invalid.content[0].type, 'text');
      assert.match(invalid.content[0].text, /unknown tool "does_not_exist"/);
      assert.equal(stderr, '', 'server startup and dispatch must not emit errors on stderr');

      await client.close();
      assert.equal(transport.pid, null);
      assert.throws(() => process.kill(childPid, 0), { code: 'ESRCH' },
        'closing the SDK client must terminate its stdio server child');
    } catch (error) {
      if (stderr) t.diagnostic(`Server stderr: ${stderr}`);
      throw error;
    }
  });
