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
      'cargo (no args: supplies, fuel, crew, marines and credits on the local player fleet, the three ' +
      'capacities with used/free, and an overloaded flag naming which limit is past), ' +
      'colonizable{limit?, maxLy?, neutralOnly?} (uncolonized planets nearest the local player fleet; ' +
      'neutralOnly keeps only systems with no economy market, i.e. no faction presence), ' +
      'hirable{limit?, maxLy?} (the markets holding officers or administrators for hire, nearest the ' +
      'local player fleet first, with per-market officer and admin counts and the people themselves), ' +
      'landmarks{kinds?, limit?, maxLy?} (hypershunts, cryosleepers, gates, stable locations, ' +
      'the gate hauler), ' +
      'entities{system?, kinds?, includeClutter?} (everything in one location - system id or name, ' +
      '"hyperspace", or the player fleet\'s location by default - as planet/station/jumpPoint/relay/' +
      'base/fleet/other rows with id, name, type, faction, x/y, tags, orbitFocus, hidden, ' +
      'discoverable and marketId; this is how you get a hidden pirate/Path base\'s id, which ss_act ' +
      'teleport accepts and whose marketId is the key to look up in ss_status baseMarketIds. ' +
      'Asteroids, orbital junk, ring bands and non-debris terrain are dropped by default - in an ' +
      'asteroid-heavy system they ate the whole 300-row cap and the gate never appeared - and ' +
      'clutterExcluded says so in the response; passing kinds at all, or includeClutter:true, ' +
      'restores the unfiltered walk), ' +
      'intel{filter?, limit?} (the player\'s intel entries with class, title, tags, isNew/isEnding/' +
      'isEnded/important, factionId and an extra block - progress and factor names for event intel, ' +
      'key numbers for the mod\'s own pages - plus a hostileActivity block that answers present/' +
      'progress/factors directly, which is the "no Hostile Activity on the guest" check), ' +
      'feed{limit?} (the last 200 co-op feed lines this instance posted, oldest last, with kind, ' +
      'colour and wall-clock stamp; survives the session ending, so it answers what the screen said ' +
      'when the link died), ' +
      'screen (state, paused, dialogOpen, interactionDialog + target, coreTab, menuOpen, the coop ' +
      'dialog requested or shown, and the same pause block ss_status carries); ' +
      'status, markets and barpool take none. ' +
      'colonizable, landmarks and entities rows carry x/y, the location-local coordinates ss_act ' +
      'teleport takes alongside systemId.',
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
      'Args by verb: teleport{entityId} or teleport{x,y,locationId|system} (the two modes are mutually ' +
      'exclusive; entityId resolves any entity in the sector - hidden bases included, use ss_dump ' +
      'entities to get one - and parks the fleet just outside it, while the coordinate mode takes a ' +
      'system id, a system name or "hyperspace"; a teleport that crosses locations runs the engine ' +
      'jump transition, so it completes over ' +
      'the next few seconds of game time rather than instantly), pause{on|off}, ability{abilityId}, ' +
      'setcr{value, memberIndex|"all"}, ' +
      'give{commodityId?, qty?, credits?}, addship{variantId, count?} (adds combat-ready ships to the ' +
      'local player fleet; an unknown variant is refused by name and count is capped at 20), ' +
      'objective{entityId, factionId}, surveyset{planetId, level}, ' +
      'expedition{factionId?} (host only: forces a punitive expedition against a player colony), ' +
      'rep{factionId, value|points} (host only: sets the player faction\'s standing with factionId; ' +
      'value is -1..1 in API units, points is -100..100 as shown in the UI, exactly one is required), ' +
      'netfault{mode: discard|loss|clear, seconds, lossPercent, delaySeconds} (makes THIS instance ' +
      'stop hearing its peer for seconds (1..180) so a link drop can be reproduced: discard throws ' +
      'away all inbound bytes, loss drops lossPercent of inbound datagrams, clear ends it now; ' +
      'delaySeconds (0..60, default 0) ARMS the fault instead of starting it, which gives the human ' +
      'at the two game windows a countdown on the HUD to get out of a menu first, and clear while ' +
      'armed cancels it (wasArmed:true); outbound is never affected, so a symmetric outage is the ' +
      'verb on both instances, and ss_status carries a netfault block with armed and ' +
      'startsInSeconds while one is scheduled or running), ' +
      'save{force?} (takes this instance\'s own vanilla autosave, which fires the same beforeGameSave/' +
      'afterGameSave hooks an F5 does and therefore sends the guest its SAVE_CHECKPOINT; refused on ' +
      'the guest unless force:true, because a guest save the host did not order is aligned with no ' +
      'host save, and refused while any dialog is open, because autosave() is silently a no-op then), ' +
      'mark{text} (writes one "Coop MARK <text>" INFO line in this instance\'s log and returns its ' +
      'atMillis, so two logs can be lined up per step; needs no campaign), ' +
      'memory{scope, key, entityId?, value?, expireDays?} (one campaign-memory key. scope is ' +
      '"global" for sector memory - the $global. namespace rules.csv uses - "player" for the player ' +
      'fleet\'s memory, or "entity" with entityId, resolved the way teleport resolves one. key takes ' +
      'any spelling: canScanGates, $canScanGates and $global.canScanGates all mean sector memory\'s ' +
      '"$canScanGates". With no value it reads and returns {scope, key, present, value, type}; with ' +
      'a value (boolean, number or string; numbers are stored as the Float MemoryAPI keeps) it ' +
      'writes and returns {scope, key, before, after}, and logs a WARN line either way. Both roles, ' +
      'reads and writes alike - it is a test harness. The case it exists for: the vanilla "Scan the ' +
      'Gate" option is gated on $global.canScanGates, which only the At the Gates story sets, so ' +
      'setting it on the host is the only way to exercise the flag\'s replication to the guest and ' +
      'the guest\'s own scan). ' +
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
