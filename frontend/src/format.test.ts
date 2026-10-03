import { describe, expect, it } from 'vitest';
import { formatDuration, formatRelative, leaseRemaining } from './format';

describe('formatDuration', () => {
  it.each([
    [0, '0ms'],
    [450, '450ms'],
    [1_200, '1.2s'],
    [45_000, '45s'],
    [190_000, '3m 10s'],
    [7_500_000, '2h 5m'],
    [-5, '0ms'],
  ])('%d ms is %s', (millis, expected) => {
    expect(formatDuration(millis)).toBe(expected);
  });
});

describe('formatRelative', () => {
  const now = Date.parse('2026-01-01T00:00:00Z');

  it('looks forward and back', () => {
    expect(formatRelative('2026-01-01T00:00:12Z', now)).toBe('in 12s');
    expect(formatRelative('2025-12-31T23:54:58Z', now)).toBe('5m 2s ago');
  });
});

describe('leaseRemaining', () => {
  const now = Date.parse('2026-01-01T00:00:00Z');

  it('is the fraction of the lease still to run', () => {
    expect(leaseRemaining('2026-01-01T00:00:30Z', 30, now)).toBe(1);
    expect(leaseRemaining('2026-01-01T00:00:15Z', 30, now)).toBe(0.5);
  });

  it('stays between 0 and 1 when clocks disagree or the lease has run out', () => {
    expect(leaseRemaining('2025-12-31T23:59:00Z', 30, now)).toBe(0);
    expect(leaseRemaining('2026-01-01T00:01:00Z', 30, now)).toBe(1);
  });
});
