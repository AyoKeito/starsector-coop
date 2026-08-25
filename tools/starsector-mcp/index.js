#!/usr/bin/env node
// starsector-mcp: MCP stdio server wrapping the two CoopAgentBridge sockets
// (host 7801, guest 7802) into query / act / diff tools.
//
// Phase 30 of the coop mod. Read-only dumps, setup actions and server-side diffing;
// no UI synthesis, no input injection, no screenshots.

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';

import {
  ACTION_VERBS,
  Bridges,
  INSTANCES,
  QUERY_VERBS,
  ssAct,
  ssAdvanceDays,
  ssDiff,
  ssDump,
  ssStatus
} from './lib/tools.js';

const instanceSchema = {
  type: 'string',
  enum: INSTANCES,
  description: 'Which running game to talk to.'
};

const argsSchema = {
  type: 'object',
  additionalProperties: true,
  description: 'Verb arguments, passed to the bridge verbatim.'
};

const TOOLS = [
  {
    name: 'ss_status',
    description:
      'Bridge status verb: role, sessionActive, campaign clock date + timestamp, paused, player fleet location and position.',
    inputSchema: {
      type: 'object',
      properties: { instance: instanceSchema },
      required: ['instance']
    }
  },
  {
    name: 'ss_dump',
    description:
      `Run one read-only bridge verb against one instance and return its JSON. Verbs: ${QUERY_VERBS.join(', ')}. ` +
      'Args by verb: fleets{locationId?}, market{marketId}, survey{systemId|"all"}, visibility{fleetId?}; status and barpool take none.',
    inputSchema: {
      type: 'object',
      properties: {
        instance: instanceSchema,
        what: { type: 'string', enum: QUERY_VERBS, description: 'Query verb.' },
        args: argsSchema
      },
      required: ['instance', 'what']
    }
  },
  {
    name: 'ss_diff',
    description:
      'Run the same read-only verb against both instances and diff the two JSON trees field by field. ' +
      'Keyed collections (fleets by coopFleetId, stock by id) compare order-insensitively. ' +
      'Returns equal, differences[{path, host, guest}] and counts{host, guest, differing} where host/guest are leaf-value counts.',
    inputSchema: {
      type: 'object',
      properties: {
        what: { type: 'string', enum: QUERY_VERBS, description: 'Query verb to run on both instances.' },
        args: argsSchema,
        tolerance: {
          type: 'number',
          description: 'Absolute tolerance for numeric leaves. Default 0 (exact match).'
        }
      },
      required: ['what']
    }
  },
  {
    name: 'ss_act',
    description:
      `Run one state-changing bridge verb against one instance. Verbs: ${ACTION_VERBS.join(', ')}. ` +
      'Args by verb: teleport{x,y,locationId}, pause{on|off}, ability{abilityId}, setcr{value, memberIndex|"all"}, ' +
      'give{commodityId?, qty?, credits?}, objective{entityId, factionId}, surveyset{planetId, level}. ' +
      'Market buy/sell, officer hire, bar-offer accept and market open/close are deliberately absent.',
    inputSchema: {
      type: 'object',
      properties: {
        instance: instanceSchema,
        verb: { type: 'string', enum: ACTION_VERBS, description: 'Action verb.' },
        args: argsSchema
      },
      required: ['instance', 'verb']
    }
  },
  {
    name: 'ss_advance_days',
    description:
      'Unpause the host, wait for its campaign clock to advance N game days, then pause it again. ' +
      'One game day is about 10 real seconds at normal speed, so budget accordingly. ' +
      'Returns the achieved clock delta, start and end dates, and whether the timeout was hit.',
    inputSchema: {
      type: 'object',
      properties: {
        days: { type: 'number', description: 'Game days to advance. Must be positive.' },
        timeoutSeconds: {
          type: 'number',
          description: 'Wall-clock budget. Default: 3x the nominal 10 s per game day plus 20 s.'
        }
      },
      required: ['days']
    }
  }
];

const bridges = new Bridges(process.env);

async function dispatch(name, args) {
  switch (name) {
    case 'ss_status':
      return ssStatus(bridges, args.instance);
    case 'ss_dump':
      return ssDump(bridges, args.instance, args.what, args.args);
    case 'ss_diff':
      return ssDiff(bridges, args.what, args.args, { tolerance: args.tolerance });
    case 'ss_act':
      return ssAct(bridges, args.instance, args.verb, args.args);
    case 'ss_advance_days':
      return ssAdvanceDays(bridges, args.days, { timeoutSeconds: args.timeoutSeconds });
    default:
      throw new Error(`unknown tool "${name}"`);
  }
}

async function main() {
  const server = new Server(
    { name: 'starsector-mcp', version: '1.0.0' },
    { capabilities: { tools: {} } }
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args = {} } = request.params;
    try {
      const data = await dispatch(name, args);
      return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] };
    } catch (err) {
      return { content: [{ type: 'text', text: err.message ?? String(err) }], isError: true };
    }
  });

  process.on('SIGINT', () => {
    bridges.closeAll();
    process.exit(0);
  });

  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  process.stderr.write(`[starsector-mcp] fatal: ${err.stack ?? err}\n`);
  process.exit(1);
});
