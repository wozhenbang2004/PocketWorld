import { createRoot } from 'react-dom/client';
import { useEffect } from 'react';
import App from './App';
import { bridge } from './bridge';
import './fonts.css';
import './app.css';
import './library.css';
import './secondary.css';
import './product.css';
import './workspace-refactor.css';

const element = document.getElementById('app');
if (!element) throw new Error('PocketWorld application mount is missing');
function MountedApp() {
  useEffect(() => {
    let disposed = false;
    // This only opens the UI subscription; it never creates or resumes a user task.
    console.info('PocketWebUi startup phase=fonts-requested');
    void Promise.all([
      document.fonts.load('400 16px "Inter"', 'PocketWorld'),
      document.fonts.load('400 16px "Noto Sans SC"', '工作预览与任务'),
      document.fonts.load('400 13px "JetBrains Mono"', 'const task = 1;'),
    ]).then(async faces => {
      if (faces.some(values => values.length === 0)) throw new Error('Bundled application fonts are missing');
      if (!disposed) console.info('PocketWebUi startup phase=fonts-loaded');
      await document.fonts.ready;
      if (!disposed) console.info('PocketWebUi startup phase=fonts-ready');
      await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
      if (disposed) return;
      console.info('PocketWebUi startup phase=frames-ready');
      console.info('PocketWebUi startup phase=handshake-sent');
      await bridge.command('app.ready');
      if (!disposed) console.info('PocketWebUi startup phase=handshake-accepted');
      // A command receipt can arrive before its state snapshot. Wait for the
      // actual App subscription and painted surface, not merely the handshake.
      await new Promise<void>((resolve, reject) => {
        let frame = 0;
        const timeout = window.setTimeout(() => {
          cancelAnimationFrame(frame);
          reject(new Error('Application state did not reach the interactive surface'));
        }, 30_000);
        const check = () => {
          if (disposed) { window.clearTimeout(timeout); resolve(); return; }
          const state = bridge.getState();
          if (state.host?.connected && state.host.ready && state.capabilities.commands.length > 0 &&
              document.documentElement.dataset.pocketUi === 'ready') {
            window.clearTimeout(timeout);
            requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
          } else frame = requestAnimationFrame(check);
        };
        check();
      });
      if (!disposed) console.info('PocketWebUi startup phase=interactive-ready');
      if (!disposed) console.info('PocketWebUi ready source=library-web');
    }).catch(error => {
      if (!disposed) console.error('PocketWebUi startup-error', error instanceof Error ? error.message : 'unknown');
    });
    return () => { disposed = true; };
  }, []);
  return <App />;
}
createRoot(element).render(<MountedApp />);
