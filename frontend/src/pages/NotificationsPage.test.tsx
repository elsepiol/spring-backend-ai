import { act, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakeApi, fakeToken, json } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';
import { POLL_INTERVAL_MS } from './NotificationsPage';

describe('notifications', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it('polls, so a notification written by the background worker shows up without a reload', async () => {
    const placed = { id: 1, message: 'Order #12 was placed. Total: 25.00', createdAt: '2026-01-01T00:00:00Z' };
    const server = fakeApi().on('GET /notifications', json([]), json([placed]));
    renderApp('/notifications', server.fetch, { token: fakeToken() });
    expect(await screen.findByText('Nothing yet.')).toBeInTheDocument();

    await act(() => vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS));

    expect(await screen.findByText(/Order #12 was placed/)).toBeInTheDocument();
  });

  it('stops polling when you leave the page', async () => {
    const server = fakeApi().on('GET /notifications', json([]));
    const { unmount } = renderApp('/notifications', server.fetch, { token: fakeToken() });
    await screen.findByText('Nothing yet.');
    const before = server.calls.length;

    unmount();
    await act(() => vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3));

    expect(server.calls.length).toBe(before);
  });
});
