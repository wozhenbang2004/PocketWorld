import type { ReactNode } from 'react';
import './workspace-section-heading.css';

/** SettingsModal's group heading relationship, adapted to full-page settings. */
export function WorkspaceSectionHeading({ title, description, actions }: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return <header className="pw-workspace-section-heading">
    <div className="pw-workspace-section-copy">
      <h2>{title}</h2>
      {description && <div className="pw-workspace-section-description">{description}</div>}
    </div>
    {actions && <div className="pw-workspace-section-actions">{actions}</div>}
  </header>;
}
