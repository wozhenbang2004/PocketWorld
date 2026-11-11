import { useState, type ReactNode } from 'react';
import { Accordion, AccordionItem } from './forms-library';
import { Tabs, TabList, Tab, TabPanel } from './shell-library';
import './project-work-launcher.css';

type StartView = (active: boolean, onBusy: (busy: boolean) => void) => ReactNode;
export type ProjectWorkPresentation = { open: boolean; mode: 'default' | 'profile' };

/** One place to start a project job. The retained BoardUI disclosure and tabs
 * keep both original forms mounted; only the selected one owns a native window.
 * App retains the selected method and disclosure when preparation opens another page. */
export function ProjectWorkLauncher({ active, defaultStart, profileStart, resumeProfile = false, presentation, onPresentationChange }: {
  active: boolean; defaultStart?: StartView; profileStart?: StartView; resumeProfile?: boolean;
  presentation: ProjectWorkPresentation; onPresentationChange: (value: ProjectWorkPresentation) => void;
}) {
  const { open, mode } = presentation;
  const [defaultBusy, setDefaultBusy] = useState(false), [profileBusy, setProfileBusy] = useState(false);
  const busy = defaultBusy || profileBusy;
  const both = Boolean(defaultStart && profileStart);
  return <Accordion className="pw-project-launcher" variant="divided" expandedKeys={open ? ['start'] : []}
    onExpandedChange={keys => { if (!busy) onPresentationChange({ ...presentation, open: keys.has('start') }); }}>
    <AccordionItem id="start" title="新项目工作" description={resumeProfile ? '继续核对这次项目工作，或填写下一个目标' : '交代目标，选择工作方式，再核对开始'} isDisabled={busy}>
      {both ? <Tabs selectedKey={mode} keyboardActivation="manual" onSelectionChange={key => { if (!busy) onPresentationChange({ ...presentation, mode: key === 'profile' ? 'profile' : 'default' }); }}>
        <TabList aria-label="新工作使用的方式"><Tab id="default" isDisabled={busy}>默认模型</Tab><Tab id="profile" isDisabled={busy}>工作配置</Tab></TabList>
        <TabPanel id="default" shouldForceMount hidden={mode !== 'default'}>{defaultStart?.(active && open && mode === 'default', setDefaultBusy)}</TabPanel>
        <TabPanel id="profile" shouldForceMount hidden={mode !== 'profile'}>{profileStart?.(active && open && mode === 'profile', setProfileBusy)}</TabPanel>
      </Tabs> : defaultStart ? defaultStart(active && open, setDefaultBusy) : profileStart?.(active && open, setProfileBusy)}
    </AccordionItem>
  </Accordion>;
}
