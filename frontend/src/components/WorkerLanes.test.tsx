import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Worker } from '../types';
import { WorkerLanes } from './WorkerLanes';

const now = Date.parse('2026-01-01T00:00:00Z');

const worker = (over: Partial<Worker>): Worker => ({
  id: 'host-abc123',
  concurrency: 3,
  startedAt: '2025-12-31T23:00:00Z',
  lastSeenAt: '2026-01-01T00:00:00Z',
  alive: true,
  local: true,
  running: [{ id: '11111111-aaaa', type: 'sleep', attempt: 2, leaseExpiresAt: '2026-01-01T00:00:15Z' }],
  ...over,
});

const noop = () => {};

afterEach(cleanup);

describe('WorkerLanes', () => {
  it('draws a box for every slot, busy or free', () => {
    render(<WorkerLanes workers={[worker({})]} leaseSeconds={30} now={now} onKill={noop} onSelectJob={noop} />);

    expect(screen.getAllByLabelText('Free slot')).toHaveLength(2);
    expect(screen.getByLabelText('sleep 11111111, attempt 2')).toBeTruthy();
    expect(screen.getByText('1 of 3 busy')).toBeTruthy();
  });

  it('shows how much of the lease is left', () => {
    const { container } = render(
      <WorkerLanes workers={[worker({})]} leaseSeconds={30} now={now} onKill={noop} onSelectJob={noop} />,
    );

    expect((container.querySelector('.lease span') as HTMLElement).style.width).toBe('50%');
  });

  it('offers to kill only a live worker hosted by this instance', () => {
    const onKill = vi.fn();
    render(
      <WorkerLanes
        workers={[worker({}), worker({ id: 'remote', local: false }), worker({ id: 'gone', alive: false })]}
        leaseSeconds={30}
        now={now}
        onKill={onKill}
        onSelectJob={noop}
      />,
    );

    const buttons = screen.getAllByRole('button', { name: 'Kill' });
    expect(buttons).toHaveLength(1);
    fireEvent.click(buttons[0]);
    expect(onKill).toHaveBeenCalledWith('host-abc123');
    expect(screen.getByText('Not responding')).toBeTruthy();
  });

  it('says what an empty fleet means', () => {
    render(<WorkerLanes workers={[]} leaseSeconds={30} now={now} onKill={noop} onSelectJob={noop} />);

    expect(screen.getByText(/queued jobs will wait/)).toBeTruthy();
  });
});
