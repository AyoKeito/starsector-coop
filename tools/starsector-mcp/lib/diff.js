// Structural JSON diff. All ss_diff comparison logic lives here: the mod bridge
// only serializes state, it never compares anything.
//
// Arrays are compared order-insensitively when they look like a keyed collection —
// every element on both sides carries the same identifying field and the values of
// that field are unique within each side. Fleets key on coopFleetId, market stock on
// id, and so on. Arrays that fail that test (a ship roster with two Wolves, for
// instance) fall back to index-by-index comparison, which is the conservative choice:
// a reordering shows up as a difference instead of being silently accepted.

/**
 * Key names excluded from the comparison at any depth unless the caller passes its own list.
 *
 * `role` and `engineId` are per-instance by definition: the host is the host and the guest is the
 * guest, and the engine's fleet ids are assigned locally on each client. `lines` is the visibility
 * probe's text dump, where the two sides describe the same fact in different sentences; its
 * structured `view` counterpart is what actually compares. Diffing any of the three reports a
 * difference on every run and buries the ones that mean something. Native orbit-focus and dialog
 * target ids are metadata too; their canonical reference fields remain part of the comparison.
 */
export const DEFAULT_IGNORE_KEYS = ['role', 'engineId', 'orbitFocusEngineId', 'interactionTargetEngineId', 'lines'];

/** Native labels/catalog numbers do not determine temporary encounter gameplay or identity. */
export const ENCOUNTER_COSMETIC_KEYS = [
  'name', 'shipName', 'firstName', 'lastName', 'displayName', 'variantDisplayName', 'catalogId'
];

/** Local ids and the engine's LocationToken cache defect are diagnostic metadata. */
export const ENCOUNTER_NATIVE_DIAGNOSTIC_KEYS = ['engineId', 'nativeSectorResolves', 'nativeSectorLookupError'];

const STORM_CELL_STATES = new Set(['ABSENT', 'OFF', 'WAIT', 'SIGNAL', 'STORM', 'STORM_WANE']);
const STORM_CELL_NUMBERS = ['wait', 'signal', 'wane', 'maxSignal', 'maxWane', 'timeScale'];
const STORM_MAX_INDEX = 2147483647;

/** Compare the same global cells at the same raw clock and authoritative frame anchor. */
export function diffStorms(host, guest, options = {}) {
  if (options.tolerance !== undefined && options.tolerance !== 0) {
    throw new Error('storms requires exact comparison; tolerance must be 0');
  }
  if (options.ignore !== undefined && (!Array.isArray(options.ignore) || options.ignore.length !== 0)) {
    throw new Error('storms gameplay fields cannot be ignored');
  }
  const maxDifferences = options.maxDifferences ?? 100;
  if (!Number.isInteger(maxDifferences) || maxDifferences < 1 || maxDifferences > 1000) {
    throw new Error('maxDifferences must be an integer from 1 to 1000');
  }
  const hostCells = validateManifest(host, 'host');
  const guestCells = validateManifest(guest, 'guest');
  const availability = { host: host.available, guest: guest.available };
  if (!host.available || !guest.available) {
    return { equal: false, status: 'INCONCLUSIVE', availability,
      reason: 'storms diagnostics unavailable; a missing legacy/native storm world is not an empty grid',
      unavailable: { host: host.available ? null : host, guest: guest.available ? null : guest },
      differences: [], ignored: [], counts: { host: 0, guest: 0, differing: 0 }, maxDifferences, truncated: false };
  }
  const common = [...hostCells.keys()].filter((key) => guestCells.has(key)).sort(compareStormCellKeys);
  const hostOnly = [...hostCells.keys()].filter((key) => !guestCells.has(key)).sort(compareStormCellKeys);
  const guestOnly = [...guestCells.keys()].filter((key) => !hostCells.has(key)).sort(compareStormCellKeys);
  const requested = options.requestedCells ?? [];
  if (!Array.isArray(requested) || requested.some((key) => typeof key !== 'string' || !/^\d+,\d+$/.test(key))) {
    throw new Error('storms requestedCells must contain global i,j keys');
  }
  const missingRequested = { host: requested.filter((key) => !hostCells.has(key)),
    guest: requested.filter((key) => !guestCells.has(key)) };
  const project = (table, cells) => ({
    width: table.width, height: table.height, gridHash: table.gridHash,
    generation: table.generation, commitTimestamp: table.commitTimestamp,
    cells: Object.fromEntries(common.map((key) => [key, cells.get(key)]))
  });
  const result = diffJson(project(host, hostCells), project(guest, guestCells), { tolerance: 0, ignore: [] });
  const calendarMatched = host.timestamp === guest.timestamp;
  const snapshotMatched = host.snapshotTimestamp === guest.snapshotTimestamp && host.revision === guest.revision;
  const reasons = [];
  if (!calendarMatched) reasons.push('storms require matching raw campaign timestamps; hold the shared pause and sample again');
  if (!snapshotMatched) reasons.push('storms authoritative snapshot/revision lag differs; wait for the same frame anchor and sample again');
  if (host.snapshotTimestamp > host.timestamp || guest.snapshotTimestamp > guest.timestamp) {
    reasons.push('a storm snapshot is ahead of its local campaign clock');
  }
  if (host.pendingControlCount || guest.pendingControlCount) reasons.push('storm controls are still pending');
  if (!common.length) reasons.push('no common global storm cells were sampled; specify the same i,j selectors');
  if (missingRequested.host.length || missingRequested.guest.length) reasons.push('an explicit global storm cell was not returned');
  const status = reasons.length ? 'INCONCLUSIVE' : result.equal ? 'PASS' : 'FAIL';
  return {
    ...result, equal: status === 'PASS', status, availability, calendarMatched, snapshotMatched,
    campaignTimestamps: { host: host.timestamp, guest: guest.timestamp },
    snapshots: { host: snapshotMetadata(host), guest: snapshotMetadata(guest) },
    sampleCoverage: { commonGlobalCells: common, hostOnlyGlobalCells: hostOnly, guestOnlyGlobalCells: guestOnly,
      missingRequestedGlobalCells: missingRequested },
    comparedCellCount: common.length,
    ...(reasons.length ? { reason: reasons.join('; '), reasons } : {}),
    differences: result.differences.slice(0, maxDifferences),
    truncated: result.differences.length > maxDifferences, maxDifferences
  };
}

function snapshotMetadata(table) {
  return { revision: table.revision, generation: table.generation, commitTimestamp: table.commitTimestamp,
    snapshotTimestamp: table.snapshotTimestamp, localLagMillis: table.timestamp - table.snapshotTimestamp,
    trackerCount: table.trackerCount, pendingControlCount: table.pendingControlCount };
}

function validateManifest(table, instance) {
  if (!isPlainObject(table) || typeof table.available !== 'boolean' || table.role !== instance.toUpperCase()) {
    throw new Error(`storms requires explicit availability and role ${instance.toUpperCase()} on ${instance}`);
  }
  if (!Number.isSafeInteger(table.pendingControlCount) || table.pendingControlCount < 0) {
    throw new Error(`storms requires a nonnegative pendingControlCount on ${instance}`);
  }
  if (!table.available) return new Map();
  for (const field of ['timestamp', 'commitTimestamp', 'snapshotTimestamp']) {
    if (!Number.isSafeInteger(table[field])) throw new Error(`storms requires an exact safe-integer ${field} on ${instance}`);
  }
  for (const field of ['revision', 'generation', 'trackerCount']) {
    if (!Number.isSafeInteger(table[field]) || table[field] < 0) {
      throw new Error(`storms requires a nonnegative safe-integer ${field} on ${instance}`);
    }
  }
  for (const field of ['width', 'height']) {
    if (!Number.isInteger(table[field]) || table[field] < 1 || table[field] > STORM_MAX_INDEX) {
      throw new Error(`storms requires a positive ${field} on ${instance}`);
    }
  }
  if (!Number.isSafeInteger(table.width * table.height) || table.trackerCount > table.width * table.height
      || typeof table.gridHash !== 'string' || !/^[0-9a-f]{64}$/.test(table.gridHash)) {
    throw new Error(`storms has an invalid grid identity or trackerCount on ${instance}`);
  }
  if (!Array.isArray(table.cells) || table.cells.length > 128) {
    throw new Error(`storms requires at most 128 global cell rows on ${instance}`);
  }
  const cells = new Map();
  for (const row of table.cells) {
    if (!isPlainObject(row) || !Number.isInteger(row.i) || !Number.isInteger(row.j)
        || row.i < 0 || row.j < 0 || row.i >= table.width || row.j >= table.height) {
      throw new Error(`storms cell coordinates must be global grid indices on ${instance}`);
    }
    const key = `${row.i},${row.j}`;
    if (cells.has(key)) throw new Error(`storms has a duplicate global cell on ${instance}: ${key}`);
    if (!Number.isInteger(row.gridValue) || row.gridValue < 0 || row.gridValue > 2
        || !STORM_CELL_STATES.has(row.state) || !Number.isSafeInteger(row.epoch) || row.epoch < 0
        || STORM_CELL_NUMBERS.some((field) => typeof row[field] !== 'number' || !Number.isFinite(row[field])
          || Math.abs(row[field]) > 1_000_000)
        || row.timeScale < 0 || row.timeScale > 100) {
      throw new Error(`storms has an invalid native cell row on ${instance}: ${key}`);
    }
    if (row.state === 'ABSENT' && (row.epoch !== 0 || STORM_CELL_NUMBERS.some((field) => row[field] !== 0))) {
      throw new Error(`storms ABSENT cell must have zero timers on ${instance}: ${key}`);
    }
    cells.set(key, Object.fromEntries(['i', 'j', 'gridValue', 'state', 'epoch', ...STORM_CELL_NUMBERS].map((field) => [field, row[field]])));
  }
  return cells;
}

function compareStormCellKeys(a, b) {
  const [ai, aj] = a.split(',').map(Number); const [bi, bj] = b.split(',').map(Number);
  return ai - bi || aj - bj;
}

const LIGHT_ID = /^light:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const LIGHT_NUMERIC_FIELDS = ['x', 'y', 'colorArgb', 'frequencyMultMin', 'frequencyMultMax',
  'frequencyChangeMult', 'size', 'detectedRange', 'bonusMult', 'remainingDays', 'pointsOverride', 'radius'];

/** Exact native light recipes and membership; local engine ids and actor state do not compare. */
export function diffLights(host, guest, options = {}) {
  validateLightManifest(host, 'host');
  validateLightManifest(guest, 'guest');
  if (options.tolerance !== undefined && Number(options.tolerance) !== 0) {
    throw new Error('lights requires exact comparison; tolerance must be 0');
  }
  if (options.ignore !== undefined && (!Array.isArray(options.ignore)
      || options.ignore.some((key) => key !== 'engineId'))) {
    throw new Error('lights only excludes engineId; gameplay fields cannot be ignored');
  }
  const maxDifferences = options.maxDifferences ?? 100;
  if (!Number.isInteger(maxDifferences) || maxDifferences < 1 || maxDifferences > 1000) {
    throw new Error('maxDifferences must be an integer from 1 to 1000');
  }
  const result = diffJson({ lights: host.lights }, { lights: guest.lights }, {
    tolerance: 0, ignore: ['engineId']
  });
  const calendarMatched = Number.isSafeInteger(host.campaignTimestamp)
    && Number.isSafeInteger(guest.campaignTimestamp)
    && host.campaignTimestamp === guest.campaignTimestamp;
  const status = !calendarMatched ? 'INCONCLUSIVE' : result.equal ? 'PASS' : 'FAIL';
  return {
    ...result,
    equal: status === 'PASS',
    status,
    calendarMatched,
    campaignTimestamps: { host: host.campaignTimestamp, guest: guest.campaignTimestamp },
    ...(calendarMatched ? {} : { reason: 'lights require matching safe-integer campaign timestamps; hold the shared pause and sample again' }),
    differences: result.differences.slice(0, maxDifferences).map(compactDifference),
    truncated: result.differences.length > maxDifferences,
    resolutionFailureCount: 0,
    maxDifferences
  };
}

function validateLightManifest(table, instance) {
  if (table?.truncated !== false || !isPlainObject(table.lights)) {
    throw new Error(`lights requires a complete untruncated manifest on ${instance}`);
  }
  const entries = Object.entries(table.lights);
  if (!Number.isInteger(table.count) || table.count !== entries.length
      || !Number.isInteger(table.candidateCount) || table.candidateCount !== table.count) {
    throw new Error(`lights manifest count or candidateCount is incomplete on ${instance}`);
  }
  const engineIds = new Set();
  const hostEntityIds = new Set();
  for (const [id, row] of entries) {
    if (!LIGHT_ID.test(id) || !isPlainObject(row) || row.id !== id || row.managed !== true
        || typeof row.engineId !== 'string' || !row.engineId || engineIds.has(row.engineId)) {
      throw new Error(`lights manifest requires unique managed canonical identities on ${instance}: ${id}`);
    }
    engineIds.add(row.engineId);
    if (typeof row.hostEntityId !== 'string' || !row.hostEntityId.trim()
        || hostEntityIds.has(row.hostEntityId)) {
      throw new Error(`lights manifest requires unique nonempty host entity identities on ${instance}: ${id}`);
    }
    hostEntityIds.add(row.hostEntityId);
    for (const field of LIGHT_NUMERIC_FIELDS) {
      if (typeof row[field] !== 'number' || !Number.isFinite(row[field])) {
        throw new Error(`lights manifest requires finite ${field} on ${instance}: ${id}`);
      }
    }
    if (!Number.isInteger(row.colorArgb) || row.colorArgb < -2147483648 || row.colorArgb > 2147483647
        || !Number.isSafeInteger(row.pointsOverride) || typeof row.dweller !== 'boolean'
        || typeof row.despawning !== 'boolean'
        || !row.despawning && row.remainingDays < 0
        || row.dweller && typeof row.salvageSeed !== 'string'
        || !(row.salvageSeed === null || typeof row.salvageSeed === 'string'
          && /^-?\d+$/.test(row.salvageSeed)
          && BigInt(row.salvageSeed) >= -9223372036854775808n
          && BigInt(row.salvageSeed) <= 9223372036854775807n)) {
      throw new Error(`lights manifest has an invalid native recipe on ${instance}: ${id}`);
    }
  }
}

/** Compare complete canonical membership, including role and identity-resolution checks. */
export function diffEncounters(host, guest, options = {}) {
  for (const [instance, table] of [['host', host], ['guest', guest]]) {
    if (table?.truncated !== false || !isPlainObject(table.encounters)) {
      throw new Error(`encounters requires a complete untruncated manifest on ${instance}`);
    }
  }
  const maxDifferences = options.maxDifferences ?? 100;
  if (!Number.isInteger(maxDifferences) || maxDifferences < 1 || maxDifferences > 1000) {
    throw new Error('maxDifferences must be an integer from 1 to 1000');
  }
  const result = diffJson({ encounters: host.encounters }, { encounters: guest.encounters }, {
    ...options, ignore: [...new Set([...(options.ignore ?? ENCOUNTER_COSMETIC_KEYS), ...ENCOUNTER_NATIVE_DIAGNOSTIC_KEYS])]
  });
  const failedPaths = new Map();
  for (const [instance, table] of [['host', host], ['guest', guest]]) {
    for (const [instanceId, encounter] of Object.entries(table.encounters)) {
      for (const collection of ['entities', 'untrackedEntities']) {
        for (const [entityId, entity] of Object.entries(encounter[collection] ?? {})) {
          if (entity.present !== true) continue;
          for (const field of ['canonicalResolves', 'locationResolves']) {
            if (entity[field] === true) continue;
            const path = `$.encounters.${instanceId}.${collection}.${entityId}.${field}`;
            if (!failedPaths.has(path)) failedPaths.set(path, { path, failedInstances: [],
              host: host.encounters[instanceId]?.[collection]?.[entityId]?.[field],
              guest: guest.encounters[instanceId]?.[collection]?.[entityId]?.[field],
              note: 'present entity must resolve to the same object' });
            failedPaths.get(path).failedInstances.push(instance);
          }
        }
      }
    }
  }
  let resolutionFailureCount = 0;
  for (const failure of failedPaths.values()) {
    resolutionFailureCount += failure.failedInstances.length;
    const existing = result.differences.find((difference) => difference.path === failure.path);
    if (existing) Object.assign(existing, { failedInstances: failure.failedInstances, note: failure.note });
    else result.differences.push(failure);
  }
  return {
    ...result,
    equal: result.differences.length === 0,
    counts: { ...result.counts, differing: result.differences.length },
    differences: result.differences.slice(0, maxDifferences).map(compactDifference),
    truncated: result.differences.length > maxDifferences,
    resolutionFailureCount,
    maxDifferences
  };
}

// Missing whole systems still report their exact path, without copying a megabyte recipe twice.
function compactDifference(difference) {
  const result = { ...difference };
  for (const side of ['host', 'guest']) {
    const value = result[side];
    if (Array.isArray(value)) result[side] = { kind: 'array', length: value.length };
    else if (isPlainObject(value)) result[side] = { kind: 'object', keys: Object.keys(value).slice(0, 20), keyCount: Object.keys(value).length };
    else if (typeof value === 'string' && value.length > 512) result[side] = { kind: 'string', length: value.length, preview: value.slice(0, 512) };
  }
  return result;
}

/** Field names tried, in order, when deciding whether an array is a keyed collection. */
export const KEY_FIELDS = [
  'coopFleetId',
  'engineId',
  'fleetId',
  'marketId',
  'planetId',
  'entityId',
  'barEventId',
  'systemId',
  'commodityId',
  'officerId',
  'id',
  'key',
  'name'
];

/**
 * @param {*} hostValue
 * @param {*} guestValue
 * @param {{tolerance?: number, ignore?: string[]}} [options] tolerance = absolute allowance for
 *   numeric leaves, 0 = exact. ignore = key names skipped at any depth; omitted means
 *   {@link DEFAULT_IGNORE_KEYS}, and passing a list replaces that default rather than adding to it.
 * @returns {{equal: boolean, differences: Array, ignored: string[], counts: {host: number, guest: number, differing: number}}}
 */
export function diffJson(hostValue, guestValue, options = {}) {
  const tolerance = Number(options.tolerance ?? 0);
  const ignored = options.ignore === undefined ? DEFAULT_IGNORE_KEYS : [...options.ignore];
  const ignore = new Set(ignored);
  const differences = [];
  walk('$', hostValue, guestValue, differences, tolerance, ignore);
  return {
    equal: differences.length === 0,
    differences,
    ignored,
    counts: {
      // Leaves that were actually compared: an ignored key contributes to neither side, so the
      // counts stay a usable "how much did we check" rather than counting what we skipped.
      host: leafCount(hostValue, ignore),
      guest: leafCount(guestValue, ignore),
      differing: differences.length
    }
  };
}

/** Number of primitive values in a JSON tree, ignored keys excluded. Empty containers count nothing. */
export function leafCount(value, ignore = new Set()) {
  if (Array.isArray(value)) return value.reduce((sum, item) => sum + leafCount(item, ignore), 0);
  if (isPlainObject(value)) {
    return Object.entries(value)
      .filter(([key]) => !ignore.has(key))
      .reduce((sum, [, item]) => sum + leafCount(item, ignore), 0);
  }
  return 1;
}

function walk(path, host, guest, out, tolerance, ignore) {
  const hostKind = kindOf(host);
  const guestKind = kindOf(guest);

  if (hostKind !== guestKind) {
    out.push({ path, host, guest, note: `${hostKind} on host, ${guestKind} on guest` });
    return;
  }
  if (hostKind === 'array') {
    walkArray(path, host, guest, out, tolerance, ignore);
    return;
  }
  if (hostKind === 'object') {
    walkObject(path, host, guest, out, tolerance, ignore);
    return;
  }
  if (hostKind === 'number') {
    if (!numbersEqual(host, guest, tolerance)) out.push({ path, host, guest });
    return;
  }
  if (host !== guest) out.push({ path, host, guest });
}

function walkObject(path, host, guest, out, tolerance, ignore) {
  const keys = union(Object.keys(host), Object.keys(guest));
  for (const key of keys) {
    if (ignore.has(key)) continue;
    const childPath = `${path}.${key}`;
    const inHost = Object.prototype.hasOwnProperty.call(host, key);
    const inGuest = Object.prototype.hasOwnProperty.call(guest, key);
    if (!inHost) out.push({ path: childPath, guest: guest[key], missing: 'host' });
    else if (!inGuest) out.push({ path: childPath, host: host[key], missing: 'guest' });
    else walk(childPath, host[key], guest[key], out, tolerance, ignore);
  }
}

function walkArray(path, host, guest, out, tolerance, ignore) {
  // An ignored field cannot be the identity either: engineId is per-instance, so keying on it would
  // pair up rows that are not the same fleet.
  const key = pickKeyField(host, guest, ignore);
  if (key) {
    const hostById = indexBy(host, key);
    const guestById = indexBy(guest, key);
    for (const id of union([...hostById.keys()], [...guestById.keys()])) {
      const childPath = `${path}[${key}=${id}]`;
      if (!hostById.has(id)) out.push({ path: childPath, guest: guestById.get(id), missing: 'host' });
      else if (!guestById.has(id)) out.push({ path: childPath, host: hostById.get(id), missing: 'guest' });
      else walk(childPath, hostById.get(id), guestById.get(id), out, tolerance, ignore);
    }
    return;
  }

  if (host.length !== guest.length) {
    out.push({ path: `${path}.length`, host: host.length, guest: guest.length });
  }
  const shared = Math.min(host.length, guest.length);
  for (let i = 0; i < shared; i++) walk(`${path}[${i}]`, host[i], guest[i], out, tolerance, ignore);
  for (let i = shared; i < host.length; i++) out.push({ path: `${path}[${i}]`, host: host[i], missing: 'guest' });
  for (let i = shared; i < guest.length; i++) out.push({ path: `${path}[${i}]`, guest: guest[i], missing: 'host' });
}

/**
 * The first KEY_FIELDS entry that identifies every element on both sides and is
 * unique within each side, or null when the arrays are not a keyed collection.
 */
export function pickKeyField(host, guest, ignore = new Set()) {
  if (host.length === 0 && guest.length === 0) return null;
  if (![...host, ...guest].every(isPlainObject)) return null;

  for (const field of KEY_FIELDS) {
    if (ignore.has(field)) continue;
    if (!identifiesAll(host, field)) continue;
    if (!identifiesAll(guest, field)) continue;
    if (!isUnique(host, field)) continue;
    if (!isUnique(guest, field)) continue;
    return field;
  }
  return null;
}

function identifiesAll(items, field) {
  return items.every((item) => {
    const value = item[field];
    return value !== undefined && value !== null && typeof value !== 'object';
  });
}

function isUnique(items, field) {
  const seen = new Set(items.map((item) => String(item[field])));
  return seen.size === items.length;
}

function indexBy(items, field) {
  const map = new Map();
  for (const item of items) map.set(String(item[field]), item);
  return map;
}

function numbersEqual(a, b, tolerance) {
  if (Number.isNaN(a) && Number.isNaN(b)) return true;
  if (tolerance > 0) return Math.abs(a - b) <= tolerance;
  return a === b;
}

function union(a, b) {
  return [...new Set([...a, ...b])].sort();
}

function kindOf(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
