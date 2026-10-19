import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppProvider } from './state';
import App from './App';
import { SessionProvider } from './session';
import './styles.css';
const client = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: 30_000, refetchOnWindowFocus: false } },
});
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <BrowserRouter>
        <AppProvider>
          <SessionProvider>
            <App />
          </SessionProvider>
        </AppProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
if (import.meta.env.PROD && 'serviceWorker' in navigator)
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      /* Installation is optional; browser app remains functional. */
    });
  });
