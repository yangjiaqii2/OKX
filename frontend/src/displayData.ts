export function hasAccountBalance(mode: unknown) {
  return mode === 'OKX_REAL' || mode === 'OKX_DEMO';
}

export function openPositions(positions: Record<string, unknown>[]) {
  return positions.filter((position) => {
    const size = Number(position.size);
    return Number.isFinite(size) && size !== 0;
  });
}

export function accountPositionPnl(positions: Record<string, unknown>[] | null) {
  if (!positions) return null;
  let pnl = 0;
  for (const position of positions) {
    const size = position.size;
    if (size == null || String(size).trim() === '' || !Number.isFinite(Number(size))) return null;
    if (Number(size) === 0) continue;
    const value = position.unrealizedPnl;
    if (value == null || String(value).trim() === '' || !Number.isFinite(Number(value))) return null;
    pnl += Number(value);
  }
  return pnl;
}

export function positionDirection(position: Record<string, unknown>) {
  if (String(position.posSide).toLowerCase() !== 'net') return position.posSide;
  const size = Number(position.size);
  if (!Number.isFinite(size) || size === 0) return 'net';
  return size < 0 ? 'short' : 'long';
}
