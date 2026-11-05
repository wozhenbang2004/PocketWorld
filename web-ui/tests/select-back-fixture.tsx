import { useLayoutEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { bridge } from '../src/bridge';
import { SelectField } from '../src/forms-library';
import { SettingsDialog } from '../src/forms-library/SettingsDialog';

// Test-only composition of the production dialog and Select. The parent uses
// the same page Back-handler boundary as App, beneath the transient Select.
export function mount() {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  function Fixture() {
    const [open, setOpen] = useState(true);
    useLayoutEffect(() => bridge.setBackHandler(() => {
      if (!open) return false;
      setOpen(false);
      return true;
    }), [open]);
    return <SettingsDialog isOpen={open} title="嵌套返回回归" onClose={() => setOpen(false)}>
      <SelectField label="嵌套范围" value="private" options={[{ id: 'private', label: '私人' }, { id: 'project', label: '项目' }]} onChange={() => {}} />
    </SettingsDialog>;
  }
  root.render(<Fixture />);
  return () => { root.unmount(); host.remove(); };
}
