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
  DEFAULT_IGNORE_KEYS,
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
      'Args by verb: fleets{locationId?}, market{marketId}, survey{systemId|"all"}, visibility{fleetId?}, ' +
      'colonizable{limit?, maxLy?, neutralOnly?} (uncolonized planets nearest the local player fleet; ' +
      'neutralOnly keeps only systems with no economy market, i.e. no faction presence), ' +
      'landmarks{kinds?, limit?, maxLy?} (hypershunts, cryosleepers, gates, stable locations, ' +
      'the gate hauler); status, markets and barpool take none. ' +
      'colonizable and landmarks rows carry x/y, the location-local coordinates ss_act teleport takes ' +
      'alongside systemId.',
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
      'Returns equal, differences[{path, host, guest}], ignored[] and counts{host, guest, differing} where host/guest are leaf-value counts.',
    inputSchema: {
      type: 'object',
      properties: {
        what: { type: 'string', enum: QUERY_VERBS, description: 'Query verb to run on both instances.' },
        args: argsSchema,
        tolerance: {
          type: 'number',
          description: 'Absolute tolerance for numeric leaves. Default 0 (exact match).'
        },
        ignore: {
          type: 'array',
          items: { type: 'string' },
          description:
            'Key names excluded from the comparison at any depth. Replaces the default ' +
            `[${DEFAULT_IGNORE_KEYS.join(', ')}] rather than adding to it; pass [] to compare everything.`
        }
      },
      required: ['what']
    }
  },
  {
    name: 'ss_act',
    description:
      `Run one state-changing bridge verb against one instance. Verbs: ${ACTION_VERBS.join(', ')}. ` +
      'Args by verb: teleport{entityId} or teleport{x,y,locationId} (the two modes are mutually ' +
      'exclusive; entityId resolves any entity in the sector and parks the fleet just outside it, ' +
      'and a teleport that crosses locations runs the engine jump transition, so it completes over ' +
      'the next few seconds of game time rather than instantly), pause{on|off}, ability{abilityId}, ' +
      'setcr{value, memberIndex|"all"}, ' +
      'give{commodityId?, qty?, credits?}, objective{entityId, factionId}, surveyset{planetId, level}, ' +
      'expedition{factionId?} (host only: forces a punitive expedition against a player colony). ' +
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
      'Returns the achieved clock delta, start and end dates, and whether the timeout was hit. ' +
      'On a timeout it also returns stall{instance, reason}, naming which pause intent held the clock.',
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
      return ssDiff(bridges, args.what, args.args, { tolerance: args.tolerance, ignore: args.ignore });
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
