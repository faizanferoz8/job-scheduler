import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import { describeOutcome, formatDuration, formatRelative, STATUS_LABEL } from '../format';
import type { Attempt, Job } from '../types';

interface Props {
  id: string;
  now: number;
  onClose: () => void;
}

export function JobDetail({ id, now, onClose }: Props) {
  const queryClient = useQueryClient();
  const detail = useQuery({
    queryKey: ['job', id],
    queryFn: () => api<{ job: Job; attempts: Attempt[] }>(`/api/jobs/${id}`),
    refetchInterval: 1_000,
  });
  const act = useMutation({
    mutationFn: (action: 'cancel' | 'retry') => api<Job>(`/api/jobs/${id}/${action}`, { method: 'POST' }),
    onSettled: () => queryClient.invalidateQueries(),
  });

  if (detail.error) {
    return (
      <aside className="detail">
        <p className="error" role="alert">
          {detail.error.message}
        </p>
        <button type="button" onClick={onClose}>
          Close
        </button>
      </aside>
    );
  }
  if (!detail.data) return <aside className="detail">Loading…</aside>;
  const { job, attempts } = detail.data;

  return (
    <aside className="detail" aria-label="Job details">
      <header>
        <div>
          <h2>{job.type}</h2>
          <code>{job.id}</code>
        </div>
        <button type="button" onClick={onClose} aria-label="Close job details">
          Close
        </button>
      </header>

      <dl className="facts">
        <dt>Status</dt>
        <dd>
          <span className="status" data-status={job.status}>
            {STATUS_LABEL[job.status]}
          </span>
        </dd>
        <dt>Attempts</dt>
        <dd>
          {job.attempts} of {job.maxAttempts}
        </dd>
        {job.status === 'QUEUED' && (
          <>
            <dt>{new Date(job.runAt).getTime() > now ? 'Runs' : 'Waiting since'}</dt>
            <dd>{formatRelative(job.runAt, now)}</dd>
          </>
        )}
        {job.worker && (
          <>
            <dt>Worker</dt>
            <dd>
              <code>{job.worker}</code>
            </dd>
          </>
        )}
        <dt>Priority</dt>
        <dd>{job.priority}</dd>
        <dt>Source</dt>
        <dd>{job.scheduled ? 'A schedule' : 'Enqueued directly'}</dd>
        <dt>Payload</dt>
        <dd>
          <code>{JSON.stringify(job.payload)}</code>
        </dd>
      </dl>

      {job.lastError && job.status !== 'SUCCEEDED' && <p className="error">{job.lastError}</p>}

      <div className="detail-actions">
        {job.status === 'QUEUED' && (
          <button type="button" disabled={act.isPending} onClick={() => act.mutate('cancel')}>
            Cancel job
          </button>
        )}
        {(job.status === 'DEAD' || job.status === 'CANCELLED') && (
          <button type="button" className="primary" disabled={act.isPending} onClick={() => act.mutate('retry')}>
            Retry job
          </button>
        )}
        {act.error && (
          <p className="error" role="alert">
            {act.error.message}
          </p>
        )}
      </div>

      <h3>Attempts</h3>
      {attempts.length === 0 ? (
        <p className="muted">Not started yet.</p>
      ) : (
        <ol className="attempts">
          {attempts.map((attempt) => (
            <li key={attempt.attempt} data-outcome={attempt.outcome ?? 'RUNNING'}>
              <p>
                <strong>{describeOutcome(attempt)}</strong>
                <span className="muted">
                  {attempt.finishedAt
                    ? ` after ${formatDuration(new Date(attempt.finishedAt).getTime() - new Date(attempt.startedAt).getTime())}`
                    : ` for ${formatDuration(now - new Date(attempt.startedAt).getTime())}`}
                </span>
              </p>
              <p className="muted">
                on <code>{attempt.worker}</code>, started {formatRelative(attempt.startedAt, now)}
              </p>
              {attempt.error && <p className="attempt-error">{attempt.error}</p>}
            </li>
          ))}
        </ol>
      )}
    </aside>
  );
}
