import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { JobsPage } from './pages/JobsPage';
import { SchedulesPage } from './pages/SchedulesPage';
// Fonts are bundled, so the dashboard makes no requests to anyone but its own server.
import '@fontsource-variable/geist-mono';
import '@fontsource-variable/instrument-sans';
import './styles.css';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <header className="topbar">
          <span className="brand">Job scheduler</span>
          <nav aria-label="Main">
            <NavLink to="/jobs">Jobs</NavLink>
            <NavLink to="/schedules">Schedules</NavLink>
          </nav>
        </header>
        <main>
          <Routes>
            <Route path="/jobs" element={<JobsPage />} />
            <Route path="/schedules" element={<SchedulesPage />} />
            <Route path="*" element={<Navigate to="/jobs" replace />} />
          </Routes>
        </main>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
