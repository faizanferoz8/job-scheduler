import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { formatRelative } from '../format';
import type { Overview, Schedule } from '../types';
import { useNow } from '../useNow';

const EXAMPLES = [
  { cron: '*/10 * * * * *', label: 'Every 10 seconds' },
  { cron: '0 * * * * *', label: 'Every minute' },
  { cron: '0 0 2 * * *', label: 'Daily at 02:00 UTC' },
  { cron: '0 0 9 * * MON-FRI', label: 'Weekdays at 09:00 UTC' },
];

export function SchedulesPage() {
  const queryClient = useQueryClient();
  const now = useNow(1_000);
  const schedules = useQuery({
    queryKey: ['schedules'],
    queryFn: () => api<Schedule[]>('/api/schedules'),
    refetchInterval: 2_000,
  });
  const overview = useQuery({ queryKey: ['overview'], queryFn: () => api<Overview>('/api/overview') });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['schedules'] });

  const [name, setName] = useState('');
  const [cron, setCron] = useState(EXAMPLES[0].cron);
  const [type, setType] = useState('sleep');

  const create = useMutation({
    mutationFn: () => api<Schedule>('/api/schedules', { method: 'POST', body: { name, cron, type } }),
    onSuccess: () => {
      setName('');
      void refresh();
    },
  });
  const setEnabled = useMutation({
    mutationFn: (change: { id: string; enabled: boolean }) =>
      api<Schedule>(`/api/schedules/${change.id}/enabled`, { method: 'PUT', body: { enabled: change.enabled } }),
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: (id: string) => api<void>(`/api/schedules/${id}`, { method: 'DELETE' }),
    onSettled: refresh,
  });

  const onCreate = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  const error = create.error ?? setEnabled.error ?? remove.error ?? schedules.error;

  return (
    <div className="schedules-page">
      <h2>Schedules</h2>
      <p className="muted lede">
        A schedule adds a job each time its cron expression comes due. However many instances are running, each tick
        makes exactly one job. Times are UTC.
      </p>

      <form className="enqueue" onSubmit={onCreate}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="nightly-report" required />
        </label>
        <label>
          Cron
          <input className="mono" value={cron} onChange={(e) => setCron(e.target.value)} list="cron-examples" required />
          <datalist id="cron-examples">
            {EXAMPLES.map((example) => (
              <option key={example.cron} value={example.cron}>
                {example.label}
              </option>
            ))}
          </datalist>
        </label>
        <label>
          Job type
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {(overview.data?.types ?? [type]).map((option) => (
              <option key={option}>{option}</option>
            ))}
          </select>
        </label>
        <button type="submit" className="primary" disabled={create.isPending}>
          Add schedule
        </button>
      </form>
      <p className="muted note">Six fields: second, minute, hour, day of month, month, day of week.</p>

      {error && (
        <p className="error" role="alert">
          {error.message}
        </p>
      )}

      {schedules.data?.length === 0 ? (
        <p className="empty">No schedules yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Cron</th>
              <th scope="col">Job type</th>
              <th scope="col">Next</th>
              <th scope="col">Last</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {schedules.data?.map((schedule) => (
              <tr key={schedule.id} data-disabled={!schedule.enabled}>
                <th scope="row">{schedule.name}</th>
                <td>
                  <code>{schedule.cron}</code>
                </td>
                <td>{schedule.type}</td>
                <td>{schedule.enabled ? formatRelative(schedule.nextFireAt, now) : 'Paused'}</td>
                <td>{schedule.lastFiredAt ? formatRelative(schedule.lastFiredAt, now) : 'Never'}</td>
                <td className="row-actions">
                  <button
                    type="button"
                    className="link"
                    disabled={setEnabled.isPending}
                    onClick={() => setEnabled.mutate({ id: schedule.id, enabled: !schedule.enabled })}
                  >
                    {schedule.enabled ? 'Pause' : 'Resume'}
                  </button>
                  <button type="button" className="link danger" disabled={remove.isPending} onClick={() => remove.mutate(schedule.id)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
