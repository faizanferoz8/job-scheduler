import { leaseRemaining, shortId } from '../format';
import type { Worker } from '../types';

interface Props {
  workers: Worker[];
  leaseSeconds: number;
  now: number;
  onKill: (id: string) => void;
  onSelectJob: (id: string) => void;
}

/**
 * One lane per worker, one box per slot. A box that is running a job shows its lease as
 * a bar: it drains as time passes and snaps back each time the worker's heartbeat
 * renews it. Kill a worker and its bars drain to nothing, and then its jobs appear on
 * someone else's lane.
 */
export function WorkerLanes({ workers, leaseSeconds, now, onKill, onSelectJob }: Props) {
  if (workers.length === 0) {
    return <p className="empty">No workers are running, so queued jobs will wait. Start one to begin.</p>;
  }
  return (
    <ul className="lanes">
      {workers.map((worker) => (
        <li key={worker.id} className="lane" data-alive={worker.alive}>
          <div className="lane-head">
            <code>{worker.id}</code>
            <span className="lane-state">
              {worker.alive ? `${worker.running.length} of ${worker.concurrency} busy` : 'Not responding'}
            </span>
            {worker.local && worker.alive && (
              <button type="button" className="link danger" onClick={() => onKill(worker.id)}>
                Kill
              </button>
            )}
          </div>
          <div className="slots">
            {Array.from({ length: Math.max(worker.concurrency, worker.running.length) }, (_, index) => {
              const job = worker.running[index];
              if (!job) return <div key={index} className="slot" data-busy="false" aria-label="Free slot" />;
              const remaining = leaseRemaining(job.leaseExpiresAt, leaseSeconds, now);
              return (
                <button
                  key={job.id}
                  type="button"
                  className="slot"
                  data-busy="true"
                  onClick={() => onSelectJob(job.id)}
                  aria-label={`${job.type} ${shortId(job.id)}, attempt ${job.attempt}`}
                >
                  <span className="slot-type">{job.type}</span>
                  <span className="slot-meta">
                    <code>{shortId(job.id)}</code>
                    {job.attempt > 1 && ` try ${job.attempt}`}
                  </span>
                  <span className="lease" role="presentation">
                    <span style={{ width: `${remaining * 100}%` }} data-low={remaining < 0.34} />
                  </span>
                </button>
              );
            })}
          </div>
        </li>
      ))}
    </ul>
  );
}
