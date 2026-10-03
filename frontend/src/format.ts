import type { Attempt, JobStatus } from './types';

export const STATUS_LABEL: Record<JobStatus, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  SUCCEEDED: 'Succeeded',
  DEAD: 'Dead',
  CANCELLED: 'Cancelled',
};

/** "1.2s", "45s", "3m 10s", "2h 5m": short enough for a table cell. */
export function formatDuration(millis: number): string {
  if (millis < 0) millis = 0;
  if (millis < 1_000) return `${Math.round(millis)}ms`;
  const seconds = millis / 1_000;
  if (seconds < 10) return `${seconds.toFixed(1)}s`;
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${Math.round(seconds % 60)}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** "in 12s" or "5m 2s ago", relative to now. */
export function formatRelative(iso: string, now: number): string {
  const delta = new Date(iso).getTime() - now;
  return delta > 0 ? `in ${formatDuration(delta)}` : `${formatDuration(-delta)} ago`;
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

const OUTCOME: Record<NonNullable<Attempt['outcome']>, string> = {
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  LEASE_EXPIRED: 'Worker stopped responding',
  RELEASED: 'Handed back at shutdown',
};

export function describeOutcome(attempt: Attempt): string {
  return attempt.outcome ? OUTCOME[attempt.outcome] : 'In progress';
}

/** How much of a lease is left, from 0 (expired) to 1 (just renewed). */
export function leaseRemaining(leaseExpiresAt: string, leaseSeconds: number, now: number): number {
  const left = (new Date(leaseExpiresAt).getTime() - now) / (leaseSeconds * 1_000);
  return Math.max(0, Math.min(1, left));
}
