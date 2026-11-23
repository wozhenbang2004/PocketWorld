import { Accordion, AccordionItem } from './forms-library';
import { deviceChoiceCode } from './device-task-scope';

type Kind = 'file' | 'project';
type Material = { kind: Kind; name: string; path: string; bytes: number; sha256: string; expectedVersion: string; manifestDigest?: string };
type RecipientWorkspace = { key: string; name: string; materials: Material[] };

const record = (value: unknown) => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
const digest = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const positive = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
// These are choices of where to put a file on the recipient, never absolute paths to either host.
const relative = (value: unknown): value is string => typeof value === 'string' && value.length <= 2048 &&
  !/[\\:\x00-\x1f]/.test(value) && value.split('/').every(part => part && part !== '.' && part !== '..');

function materials(value: unknown, kind: Kind): Material[] | undefined {
  const project = kind === 'project';
  if (!Array.isArray(value) || value.length > (project ? 1 : 4)) return;
  const result: Material[] = [];
  for (const candidate of value) {
    const item = record(candidate);
    if (!item) return;
    const { name, path, size, sha256, expectedVersion, manifestDigest } = item;
    if (!relative(path) || !digest(sha256) || typeof size !== 'number' || !Number.isSafeInteger(size) || size < 0 ||
        size > (project ? 20 * 1024 * 1024 : 256 * 1024) || typeof expectedVersion !== 'string' ||
        (project ? !digest(manifestDigest) || expectedVersion !== 'absent' : !/^(absent|sha256:[a-f0-9]{64})$/.test(expectedVersion))) return;
    const label = typeof name === 'string' && name.trim() && name.length <= 512 && !/[\\/:\x00-\x1f]/.test(name)
      ? name : project ? '所选项目包' : '所选原件';
    result.push({ kind, name: label, path, bytes: size, sha256, expectedVersion,
      manifestDigest: project && digest(manifestDigest) ? manifestDigest : undefined });
  }
  return result;
}

/** Only the native scope and same-peer seal already in the selected private Work detail.
 * This is a receipt of an owner choice, not a grant, source availability or delivery read. */
function savedHandoff(taskId: string, value: unknown) {
  const task = record(value);
  if (!task || task.id !== taskId || task.actorId !== 'owner' || task.spaceId !== 'private' || task.kind !== 'assistant' ||
      task.origin != null || task.parentTaskId != null || task.teamAssignment != null || task.teamRunId != null ||
      task.developmentContext != null || task.ownerDeviceWork != null || task.ownerPhoneControl != null || task.feature === 'remote' ||
      task.taskProjection !== 'detail_window' || task.taskCoreReadLimited !== false || task.auditBodyIncluded !== true ||
      task.contextAvailable === false || task.contextWithdrawn === true || task.sourceCurrentReadRequired === true) return;
  const saved = record(task.ownerSavedDeviceChoice), scope = record(task.deviceSourceScope), target = record(saved?.target);
  if (!saved || !target || !scope || saved.format !== 'pocketworld.owner-saved-device-choice.v1' ||
      saved.readOnly !== true || saved.executionAuthorized !== false || !deviceChoiceCode(saved.localDeviceId) ||
      typeof target.deviceId !== 'string' || !deviceChoiceCode(target.deviceId) || target.deviceId === saved.localDeviceId ||
      typeof target.instanceId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(target.instanceId) || !digest(target.certificateFingerprint) ||
      scope.format !== 'pocketworld.native-device-source-scope.v1' || scope.taskId !== taskId ||
      typeof task.goal !== 'string' || scope.goal !== task.goal || scope.contextMode !== 'goal_only' ||
      !positive(scope.revision) || saved.scopeRevision !== scope.revision ||
      !Array.isArray(scope.routes) || !scope.routes.length || scope.routes.length > 16) return;
  const workspaces: RecipientWorkspace[] = [];
  for (const candidate of scope.routes) {
    const route = record(candidate);
    if (!route || route.targetDeviceId !== target.deviceId || route.instanceId !== target.instanceId) return;
    const files = materials(route.files, 'file'), projects = materials(route.projects, 'project');
    if (!files || !projects) return;
    if (!files.length && !projects.length) continue;
    const binding = record(route.resourceBinding), tools = record(route.toolDeclarations);
    if (route.kind !== 'tool_session' || route.capabilityVersion !== 'workspace-v1' || route.contextMode !== 'goal_only' ||
        typeof route.optionId !== 'string' || !/^option_[a-f0-9]{64}$/.test(route.optionId) ||
        !binding || binding.kind !== 'workspace' || typeof binding.environmentId !== 'string' ||
        !/^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(binding.environmentId) || !digest(binding.binding) ||
        (files.length && tools?.['workspace.receive'] !== 'write') || (projects.length && tools?.['workspace.project.receive'] !== 'write')) return;
    workspaces.push({ key: route.optionId, name: binding.environmentId, materials: [...files, ...projects] });
  }
  const count = workspaces.reduce((total, workspace) => total + workspace.materials.length, 0);
  if (!count) return;
  return { count, workspaces, recipient: target.deviceId, revision: scope.revision };
}

export function SavedDeviceHandoffReceipt({ taskId, raw }: { taskId: string; raw: unknown }) {
  const receipt = savedHandoff(taskId, raw);
  if (!receipt) return null;
  return <Accordion key={taskId + ':' + receipt.revision} variant="divided" className="pw-compact-disclosures">
    <AccordionItem id="saved-device-handoff" title="本机保存选择"
      description={`接收设备 ${deviceChoiceCode(receipt.recipient)} · ${receipt.count} 项材料`}>
      <p className="pw-field-hint">这里只显示当时保存在本机的选择。查看不会联系对方；不表示已经发送、对方已收到或导入，也不表示原授权仍有效。</p>
      <p style={{ overflowWrap: 'anywhere' }}>原接收设备 <bdi>{receipt.recipient}</bdi></p>
      {receipt.workspaces.map(space => <section key={space.key}>
        <h4>接收方工作区 <bdi>{space.name}</bdi></h4>
        <ul>{space.materials.map(item => <li key={item.kind + ':' + item.path} style={{ overflowWrap: 'anywhere' }}>
          {item.kind === 'project' ? '项目包' : '原件'}：<bdi>{item.name}</bdi> · {item.bytes} 字节
          <br />目标相对路径：<bdi>{item.path}</bdi> · {item.expectedVersion === 'absent' ? '只允许新建' : '仅允许覆盖当时核对的版本 ' + item.expectedVersion.slice(7)}
          <br />原件 SHA-256：<bdi>{item.sha256}</bdi>
          {item.manifestDigest && <><br />项目包清单摘要：<bdi>{item.manifestDigest}</bdi></>}
        </li>)}</ul>
      </section>)}
    </AccordionItem>
  </Accordion>;
}
