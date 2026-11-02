// Pure storm-query validation and comparison. No bridge actions or native state readers.
const MAX_INDEX = 2147483647;

export function validateStormArgs(args = {}) {
  if (!object(args)) throw new Error('storms args must be an object');
  for (const key of Object.keys(args)) {
    if (key !== 'cells' && key !== 'limit') throw new Error(`unknown storms argument: ${key}`);
  }
  const limit = args.limit === undefined ? 128 : args.limit;
  if (!Number.isInteger(limit) || limit < 1 || limit > 128) {
    throw new Error('storms limit must be an integer from 1 to 128');
  }
  if (args.cells === undefined) return { limit, cells: [] };
  if (typeof args.cells !== 'string') throw new Error('storms cells must be a string of global i,j pairs');
  if (!args.cells.trim()) return { limit, cells: [] };
  const selected = args.cells.trim().split(';');
  if (selected.length > limit) throw new Error('storms cells count exceeds limit');
  const unique = new Set();
  for (const cell of selected) {
    if (!/^\s*\d+\s*,\s*\d+\s*$/.test(cell)) {
      throw new Error('storms cells must contain global nonnegative i,j pairs');
    }
    const pair = cell.split(',').map(Number);
    if (pair.some((n) => !Number.isInteger(n) || n > MAX_INDEX)) {
      throw new Error('storms cell indices must fit nonnegative 32-bit integers');
    }
    const key = pair.join(',');
    if (unique.has(key)) throw new Error(`duplicate storms cell: ${key}`);
    unique.add(key);
  }
  return { limit, cells: [...unique] };
}

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
