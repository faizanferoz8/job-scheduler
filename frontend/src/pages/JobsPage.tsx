import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { JobDetail } from '../components/JobDetail';
import { WorkerLanes } from '../components/WorkerLanes';
import { formatDuration, formatRelative, shortId, STATUS_LABEL } from '../format';
import type { Job, JobStatus, Overview } from '../types';
import { useNow } from '../useNow';

const FILTERS: (JobStatus | 'ALL')[] = ['ALL', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'DEAD', 'CANCELLED'];

const TYPE_HELP: Record<string, string> = {
  sleep: 'Takes a few seconds, then succeeds.',
  flaky: 'Fails twice, then succeeds: watch it retry with a growing delay.',
  'always-fails': 'Never succeeds: ends up dead after its attempts run out.',
};

export function JobsPage() {
  const queryClient = useQueryClient();
  const now = useNow();
  const [filter, setFilter] = useState<JobStatus | 'ALL'>('ALL');
  const [selected, setSelected] = useState<string | null>(null);

  const overview = useQuery({ queryKey: ['overview'], queryFn: () => api<Overview>('/api/overview'), refetchInterval: 1_000 });
  const jobs = useQuery({
    queryKey: ['jobs', filter],
    queryFn: () => api<Job[]>(`/api/jobs?limit=60${filter === 'ALL' ? '' : `&status=${filter}`}`),
    refetchInterval: 1_000,
  });

  const [type, setType] = useState('sleep');
  const [count, setCount] = useState(8);
  const [priority, setPriority] = useState(0);
  const [delay, setDelay] = useState(0);
  const refresh = () => queryClient.invalidateQueries();

  const enqueue = useMutation({
    mutationFn: () =>
      api<Job[]>('/api/jobs', {
        method: 'POST',
        body: { type, count, priority, delaySeconds: delay, payload: type === 'sleep' ? { millis: 4000 } : {} },
      }),
    onSuccess: refresh,
  });
  const startWorker = useMutation({ mutationFn: () => api('/api/workers', { method: 'POST' }), onSuccess: refresh });
  const killWorker = useMutation({
    mutationFn: (id: string) => api(`/api/workers/${id}`, { method: 'DELETE' }),
    onSuccess: refresh,
  });

  const onEnqueue = (event: FormEvent) => {
    event.preventDefault();
    enqueue.mutate();
  };

  const data = overview.data;
  const error = overview.error ?? jobs.error ?? enqueue.error ?? startWorker.error ?? killWorker.error;

  return (
    <div className="jobs-page" data-detail={selected !== null}>
      <div className="jobs-main">
        <section className="counts" aria-label="Queue totals">
          {(['QUEUED', 'RUNNING', 'SUCCEEDED', 'DEAD'] as const).map((status) => (
            <button
              key={status}
              type="button"
              className="count"
              data-status={status}
              aria-pressed={filter === status}
              onClick={() => setFilter(filter === status ? 'ALL' : status)}
            >
              <strong>{data ? data.counts[status].toLocaleString() : '–'}</strong>
              <span>{STATUS_LABEL[status]}</span>
            </button>
          ))}
          <div className="count plain">
            <strong>{data ? formatDuration(data.oldestDueWaitMillis) : '–'}</strong>
            <span>Longest wait</span>
          </div>
          <div className="count plain">
            <strong>{data ? data.succeededLastMinute : '–'}</strong>
            <span>Done in the last minute</span>
          </div>
        </section>

        {error && (
          <p className="error" role="alert">
            {error.message}
          </p>
        )}

        <section>
          <div className="section-head">
            <h2>Workers</h2>
            <button type="button" disabled={startWorker.isPending} onClick={() => startWorker.mutate()}>
              Start a worker
            </button>
          </div>
          {data && (
            <WorkerLanes
              workers={data.workers}
              leaseSeconds={data.leaseSeconds}
              now={now}
              onKill={(id) => killWorker.mutate(id)}
              onSelectJob={setSelected}
            />
          )}
          <p className="muted note">
            Each bar is a lease. It refills when the worker checks in. Kill a worker and its bars run out, then its jobs
            move to another worker.
          </p>
        </section>

        <section>
          <h2>Add jobs</h2>
          <form className="enqueue" onSubmit={onEnqueue}>
            <label>
              Type
              <select value={type} onChange={(e) => setType(e.target.value)}>
                {(data?.types ?? [type]).map((option) => (
                  <option key={option}>{option}</option>
                ))}
              </select>
            </label>
            <label>
              How many
              <input type="number" min={1} max={1000} value={count} onChange={(e) => setCount(Number(e.target.value))} />
            </label>
            <label>
              Priority
              <input type="number" min={-100} max={100} value={priority} onChange={(e) => setPriority(Number(e.target.value))} />
            </label>
            <label>
              Delay, seconds
              <input type="number" min={0} max={86400} value={delay} onChange={(e) => setDelay(Number(e.target.value))} />
            </label>
            <button type="submit" className="primary" disabled={enqueue.isPending}>
              Add {count === 1 ? 'job' : `${count} jobs`}
            </button>
          </form>
          {TYPE_HELP[type] && <p className="muted note">{TYPE_HELP[type]}</p>}
        </section>

        <section>
          <div className="section-head">
            <h2>Jobs</h2>
            <div className="filters" role="group" aria-label="Filter by status">
              {FILTERS.map((option) => (
                <button key={option} type="button" aria-pressed={filter === option} onClick={() => setFilter(option)}>
                  {option === 'ALL' ? 'All' : STATUS_LABEL[option]}
                </button>
              ))}
            </div>
          </div>
          {jobs.data?.length === 0 ? (
            <p className="empty">
              {filter === 'ALL' ? 'No jobs yet. Add some above.' : `No ${STATUS_LABEL[filter].toLowerCase()} jobs.`}
            </p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th scope="col">Job</th>
                  <th scope="col">Status</th>
                  <th scope="col">Attempts</th>
                  <th scope="col">When</th>
                  <th scope="col">Detail</th>
                </tr>
              </thead>
              <tbody>
                {jobs.data?.map((job) => (
                  <tr key={job.id} aria-selected={job.id === selected} onClick={() => setSelected(job.id)}>
                    <th scope="row">
                      <button type="button" className="link" onClick={() => setSelected(job.id)}>
                        {job.type}
                      </button>{' '}
                      <code>{shortId(job.id)}</code>
                    </th>
                    <td>
                      <span className="status" data-status={job.status}>
                        {STATUS_LABEL[job.status]}
                      </span>
                    </td>
                    <td>
                      {job.attempts} of {job.maxAttempts}
                    </td>
                    <td>{when(job, now)}</td>
                    <td className="cell-detail">
                      {job.status === 'RUNNING' ? <code>{job.worker}</code> : job.status !== 'SUCCEEDED' && job.lastError}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>

      {selected && <JobDetail id={selected} now={now} onClose={() => setSelected(null)} />}
    </div>
  );
}

function when(job: Job, now: number): string {
  if (job.finishedAt) return `finished ${formatRelative(job.finishedAt, now)}`;
  if (job.status === 'QUEUED') {
    const due = new Date(job.runAt).getTime();
    // A queued job with attempts behind it is waiting out its retry delay.
    if (due > now) return `${job.attempts > 0 ? 'retries' : 'runs'} ${formatRelative(job.runAt, now)}`;
    return `waiting ${formatDuration(now - due)}`;
  }
  return `added ${formatRelative(job.createdAt, now)}`;
}
