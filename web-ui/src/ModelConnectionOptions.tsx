import { Accordion, AccordionItem, Button, CheckboxField, RadioOptions, SelectField, StretchSwitch, TextField } from './forms-library';
import { cacheCounterText, cacheHintText, type CompatibilityDraft } from './model-connection-policy';

type Row = Record<string, any>;
const native = { id: 'NATIVE', label: '沿用当前协议' };

/** Uses the existing BoardUI fields inside the original connection editor. */
export function ModelConnectionOptions({ value, onChange, provider, adding, revision, section }: {
  value: CompatibilityDraft; onChange: (next: CompatibilityDraft) => void; provider: string; adding: boolean; revision?: number; section: 'wire' | 'cache';
}) {
  const change = (key: keyof CompatibilityDraft, next: string | boolean) => onChange({ ...value, [key]: next });
  const chat = provider === 'openai_chat', anthropic = provider === 'anthropic';
  return <div className="pw-form-grid" data-connection-policy>
    <p className="pw-field-hint">{adding ? '先保存这项连接，再设置兼容与缓存策略。' : revision ? `已保存兼容档案 · 修订 ${revision}。变更后先保存，再进行连接测试。` : '按这项连接保存请求策略；不会改变模型能力、工具权限或历史消息。'}</p>
    {section === 'wire' && <CheckboxField isSelected={value.enabled} isDisabled={adding} onChange={next => change('enabled', next)}>使用连接兼容档案</CheckboxField>}
    {section === 'wire' && value.enabled && <>
      <section className="pw-connection-policy-group" aria-label="输出请求格式"><h4>输出与用量</h4>
      <SelectField label="输出上限字段" value={value.tokenField} isDisabled={!chat} onChange={next => change('tokenField', next)}
        options={chat ? [native, { id: 'MAX_TOKENS', label: 'max_tokens' }, { id: 'MAX_COMPLETION_TOKENS', label: 'max_completion_tokens' }] : [native]}
        hint={chat ? '只调整当前 Chat 请求的字段；与模型目录精确要求冲突时会在发送前拒绝。' : '当前协议使用原有输出字段，不支持这项改写。'} />
      <SelectField label="流式用量请求" value={value.streamUsage} isDisabled={!chat} onChange={next => change('streamUsage', next)}
        options={chat ? [native, { id: 'OMIT', label: '省略 stream_options' }] : [native]}
        hint="连接测试只验证文字请求，不验证流式用量、图片或工具能力。" />
      </section><section className="pw-connection-policy-group" aria-label="工具请求格式"><h4>工具声明</h4>
      <SelectField label="工具声明格式" value={value.toolWireShape} isDisabled={!chat && !anthropic} onChange={next => change('toolWireShape', next)}
        options={chat || anthropic ? [native, { id: 'OPENAI_FUNCTION', label: 'OpenAI function' }, { id: 'ANTHROPIC_INPUT_SCHEMA', label: 'Anthropic input_schema' }] : [native]}
        hint="只接受可无损转换的声明；不会删除必需的工具参数。" />
      <SelectField label="工具参数规范" value={value.schemaSubset} onChange={next => change('schemaSubset', next)}
        options={[native, { id: 'CORE_JSON', label: '基础 JSON Schema' }]}
        hint="不支持的关键字会在发送前拒绝，历史消息和工具结果保持原样。" />
      </section><section className="pw-connection-policy-group" aria-label="发送前请求限制"><h4>发送前限制</h4>
      <TextField label="每次最多携带的工具数" inputMode="numeric" value={value.maxTools} onChange={next => change('maxTools', next.replace(/\D/g, '').slice(0, 3))} hint="1–512；超过时拒绝发送，不截断工具。" />
      <TextField label="请求大小上限（字节）" inputMode="numeric" value={value.maxRequestBytes} onChange={next => change('maxRequestBytes', next.replace(/\D/g, '').slice(0, 8))} hint="256–25165824；在发送前检查实际请求。" />
      </section>
    </>}
    {section === 'cache' && <>
      {!anthropic ? <p className="pw-field-hint">当前协议尚未接入显式缓存提示，保持关闭。服务商若返回缓存用量，仍按原调用回执显示。</p>
        : !value.enabled ? <><p className="pw-field-hint">缓存与请求兼容选项保存到同一份连接档案；启用后仍先核对支持状态。</p><Button variant="secondary" disabled={adding} onClick={() => change('enabled', true)}>启用连接档案</Button></>
        : <>
        <RadioOptions label="缓存支持状态" value={value.cacheSupport} onChange={next => onChange({ ...value, cacheSupport: next, cacheMode: next === 'SUPPORTED' ? value.cacheMode : 'OFF' })}
          options={[{ id: 'UNKNOWN', label: '未确认' }, { id: 'SUPPORTED', label: '已核对服务商支持' }, { id: 'UNSUPPORTED', label: '不支持' }]}
        />
        <p className="pw-field-hint">这是你对当前连接与模型的核对记录；文字测试不会自动把它改成支持。</p>
        <StretchSwitch label="请求临时前缀缓存" checked={value.cacheMode === 'EPHEMERAL'} disabled={value.cacheSupport !== 'SUPPORTED'}
          onChange={next => change('cacheMode', next ? 'EPHEMERAL' : 'OFF')}
          description={value.cacheSupport === 'SUPPORTED' ? '只为当前稳定系统与工具前缀添加提示；是否命中以服务商实际用量为准。' : '未确认或不支持时保持关闭，不添加提示，也不自动换线路重试。'} />
      </>}
      <p className="pw-field-hint">这项文字连接测试没有系统或工具前缀，因此不能验证缓存命中。缓存读取、写入和费用缺失时显示未知，不算免费。</p>
    </>}
  </div>;
}

export function ModelConnectionTestFeedback({ value }: { value: Row }) {
  const proof = value.connectionTest || {}, feedback = value.inferenceFeedback || {};
  const calls: Row[] = Array.isArray(feedback.calls) ? feedback.calls : [];
  const unknown = proof.outcome === 'unknown';
  return <section className="pw-request-notice" aria-label="本次连接测试结果" aria-live="polite">
    <h3>{unknown ? '文字测试 · 结果待核对' : proof.outcome === 'not_dispatched' ? '文字测试 · 未发出' : value.ok === true ? '文字测试 · 已返回结果' : '文字测试 · 未确认可用'}</h3>
    <p>{unknown ? '原测试结果待核对。请先查看实际调用记录，再决定是否发起新的测试。' : String(value.message || '原测试未返回说明。')}</p>
    <p className="pw-field-hint">{proof.outcome === 'not_dispatched' ? '本次没有进入模型调用。' : '仅对应本次草稿的文字测试；图片、流式用量和工具能力尚未验证。'}</p>
    <p className="pw-field-hint">{proof.connectionId ? `原连接修订 ${String(proof.connectionRevision ?? '未提供')}` : '新连接草稿'} · 草稿修订 {String(proof.draftRevision ?? '未提供')}。此回执不代表已保存配置。</p>
    <Accordion><AccordionItem id="actual-call" title="实际调用详情" description="原测试身份、协议、缓存用量与费用">
    <dl className="pw-facts"><div><dt>原测试</dt><dd>{String(proof.testId || '未提供')}</dd></div><div><dt>原连接</dt><dd>{String(proof.connectionId || '未保存的新连接')}</dd></div></dl>
    {feedback.available === true ? calls.map(call => <div key={String(call.callId)}>
      <dl className="pw-facts">
        <div><dt>实际协议</dt><dd>{String(call.protocol || '原回执未提供')}</dd></div>
        <div><dt>兼容档案</dt><dd>{call.providerProjection?.compatProfileRevision ? `修订 ${call.providerProjection.compatProfileRevision}` : '原回执未记录档案'}</dd></div>
        <div><dt>缓存提示</dt><dd>{cacheHintText(call.providerProjection)}</dd></div>
        <div><dt>缓存读取</dt><dd>{cacheCounterText(call.cacheCounters?.CACHE_READ)}</dd></div>
        <div><dt>缓存写入</dt><dd>{cacheCounterText(call.cacheCounters?.CACHE_WRITE)}</dd></div>
        {call.cacheCounters?.CACHE_WRITE_5M?.source === 'provider_reported' && <div><dt>5 分钟写入</dt><dd>{cacheCounterText(call.cacheCounters.CACHE_WRITE_5M)}</dd></div>}
        {call.cacheCounters?.CACHE_WRITE_1H?.source === 'provider_reported' && <div><dt>1 小时写入</dt><dd>{cacheCounterText(call.cacheCounters.CACHE_WRITE_1H)}</dd></div>}
        <div><dt>实际费用</dt><dd>未知；用量不是账单</dd></div>
      </dl>
    </div>) : <p className="pw-field-hint">尚无可核对的原始用量，缓存与费用保持未知。</p>}
    </AccordionItem></Accordion>
  </section>;
}
