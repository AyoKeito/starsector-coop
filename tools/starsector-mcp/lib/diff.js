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
 * difference on every run and buries the ones that mean something.
 */
export const DEFAULT_IGNORE_KEYS = ['role', 'engineId', 'lines'];

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
