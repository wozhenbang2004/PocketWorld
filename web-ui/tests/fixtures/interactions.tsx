/* Isolated interaction contract fixture, never a native runtime or production page. */
import {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {MotionConfig} from 'motion/react';
import {CommandMenu} from '../../src/shell-library/CommandMenu';
import {ReviewCenter,PermissionReview} from '../../src/shell-library/Review';
import {WheelTimeField} from '../../src/forms-library/uiux/WheelTimeField';
import {FluidValueSlider} from '../../src/forms-library/uiux/FluidValueSlider';
import '../../src/fonts.css';import '../../src/app.css';import '../../src/library.css';import '../../src/secondary.css';import '../../src/product.css';
import '../../src/shell-library/affect-inputs.css';import '../../src/shell-library/affect-review.css';
function Fixture(){
 const[time,setTime]=useState('09:30'),[budget,setBudget]=useState(30),[pending,setPending]=useState(false),[picked,setPicked]=useState(''),[approved,setApproved]=useState(0),[selected,setSelected]=useState('a');
 const items=[{id:'a',title:'核对机票价格',description:'限制与预计费用',status:'等待确认'},{id:'b',title:'整理项目文档',description:'仅选中的目录',status:'待核对'},{id:'c',title:'提醒查看更新',description:'读取原记录'}];
 return <main style={{width:'100%',maxWidth:840,minWidth:0,padding:20,margin:'0 auto',display:'grid',gridTemplateColumns:'minmax(0,1fr)',gap:24}}><h1>交互契约测试</h1>
 <section><h2>搜索</h2><CommandMenu commands={items.map(item=>({...item,group:'待处理',onSelect:()=>setPicked(item.id)}))}/><output data-testid="picked">{picked}</output></section>
 <ReviewCenter items={items} selectedId={selected} onSelect={setSelected}><PermissionReview title={items.find(item=>item.id===selected)!.title} context="本次受控核对" reason="由原任务提供的真实范围。" fields={[{label:'对象',value:selected}]} pending={pending} error={pending?'请求处理中，保留原错误状态。':undefined} onApprove={()=>setApproved(count=>count+1)} onReject={()=>setSelected('a')}><p>选中对象：{selected}</p></PermissionReview></ReviewCenter>
 <output data-testid="approved">{approved}</output><output data-testid="selected">{selected}</output>
 <button onClick={()=>setPending(value=>!value)} data-testid="pending">{pending?'解除等待':'设置等待'}</button>
 <WheelTimeField label="当地时间 HH:mm" value={time} onChange={setTime} isDisabled={pending}/><output data-testid="time">{time}</output>
 <FluidValueSlider label="本次预算" value={budget} minValue={10} maxValue={100} step={5} onChange={setBudget} disabled={pending} unit="元" hint="滑动仅修改草稿。"/><output data-testid="budget">{budget}</output>
 </main>
}
createRoot(document.getElementById('app')!).render(<MotionConfig reducedMotion="user"><Fixture/></MotionConfig>);
