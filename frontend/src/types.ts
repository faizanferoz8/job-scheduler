export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'DEAD' | 'CANCELLED';

export interface Job {
  id: string;
  queue: string;
  type: string;
  payload: unknown;
  status: JobStatus;
  priority: number;
  runAt: string;
  attempts: number;
  maxAttempts: number;
  worker: string | null;
  lastError: string | null;
  scheduled: boolean;
  createdAt: string;
  finishedAt: string | null;
}

export interface Attempt {
  attempt: number;
  worker: string;
  startedAt: string;
  finishedAt: string | null;
  outcome: 'SUCCEEDED' | 'FAILED' | 'LEASE_EXPIRED' | 'RELEASED' | null;
  error: string | null;
}

export interface RunningJob {
  id: string;
  type: string;
  attempt: number;
  leaseExpiresAt: string;
}

export interface Worker {
  id: string;
  concurrency: number;
  startedAt: string;
  lastSeenAt: string;
  alive: boolean;
  local: boolean;
  running: RunningJob[];
}

export interface Overview {
  counts: Record<JobStatus, number>;
  oldestDueWaitMillis: number;
  succeededLastMinute: number;
  workers: Worker[];
  types: string[];
  leaseSeconds: number;
}

export interface Schedule {
  id: string;
  name: string;
  cron: string;
  type: string;
  payload: unknown;
  enabled: boolean;
  nextFireAt: string;
  lastFiredAt: string | null;
}
