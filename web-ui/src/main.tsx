import { createRoot } from 'react-dom/client';
import { useEffect } from 'react';
import App from './App';
import { bridge } from './bridge';
import './fonts.css';
import './app.css';
import './library.css';
import './secondary.css';
import './product.css';

const element = document.getElementById('app');
if (!element) throw new Error('PocketWorld application mount is missing');
function MountedApp() {
  useEffect(() => {
    let disposed = false;
    // This only opens the UI subscription; it never creates or resumes a user task.
    void Promise.all([
      document.fonts.load('400 16px "Inter"', 'PocketWorld'),
      document.fonts.load('400 16px "Noto Sans SC"', '工作预览与任务'),
      document.fonts.load('400 13px "JetBrains Mono"', 'const task = 1;'),
    ]).then(async faces => {
      if (faces.some(values => values.length === 0)) throw new Error('Bundled application fonts are missing');
      await document.fonts.ready;
      await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
      if (disposed) return;
      await bridge.command('app.ready');
      if (!disposed) console.info('PocketWebUi ready source=library-web');
    }).catch(error => {
      if (!disposed) console.error('PocketWebUi startup-error', error instanceof Error ? error.message : 'unknown');
    });
    return () => { disposed = true; };
  }, []);
  return <App />;
}
createRoot(element).render(<MountedApp />);
