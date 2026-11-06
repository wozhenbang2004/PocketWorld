/** Test-only shell composition using the production BoardUI navigation/actions.
 * Synthetic text proves layout and draft continuity, not native execution. */
import {createRoot} from 'react-dom/client';
import {useState} from 'react';
import {MotionConfig} from 'motion/react';
import {RiAddLine,RiChatAiLine,RiLayoutGridLine,RiFolderLine,RiSearchLine,RiSettings4Line,RiToolsLine,RiHistoryLine} from '@remixicon/react';
import {WorkSidebar,type WorkNavItem} from '../src/shell-library/WorkSidebar';
import {Button,IconButton,Accordion,AccordionItem} from '../src/forms-library';
import {useMotionLifecycle} from '../src/library/surface-motion';
import '../src/fonts.css';
import '../src/app.css';
import '../src/library.css';
import '../src/secondary.css';
import '../src/product.css';
import '../src/workspace-refactor.css';
import '../src/presentation-theme.css';
import '../src/composer-compact.css';
import '../src/motion.css';
import '../src/uiux-surfaces.css';

const mainItems:WorkNavItem[]=[{key:'chat',label:'对话',icon:RiChatAiLine},{key:'tasks',label:'任务',icon:RiLayoutGridLine,count:3},{key:'projects',label:'项目和文件',icon:RiFolderLine}];
const tools:WorkNavItem[]=[{key:'history',label:'工作历史',icon:RiHistoryLine},{key:'teams',label:'团队工作',icon:RiToolsLine},{key:'disabled',label:'暂不可用的工作',icon:RiFolderLine,disabled:true}];
function Fixture(){
 useMotionLifecycle();
 const[compact,setCompact]=useState(false),[selected,setSelected]=useState('chat'),[draft,setDraft]=useState('这份草稿在侧栏收起后继续保留。');
 return <div className="pw-app pw-library pw-redesign" data-navigation="sidebar">
  <aside className="pw-desktop-navigation pw-navigation-drawer" data-compact={String(compact)} aria-label="侧边工作导航">
   <header className="pw-drawer-header"><h2>PocketWorld</h2></header>
   <div className="pw-navigation-actions"><Button variant="secondary" leadingIcon={RiAddLine} title="新对话">新对话</Button><IconButton icon={RiSearchLine} aria-label="搜索工作" title="搜索工作"/></div>
   <div className="pw-drawer-body">
    <WorkSidebar items={mainItems} selected={selected} onNavigate={setSelected} label="主导航" hideGroupLabels compact={compact} onCompactChange={setCompact}/>
    <section className="pw-work-recent"><div className="pw-section-heading"><h3>最近对话</h3></div><div className="pw-recent-work"><div className="pw-conversation-row"><Button variant="ghost" className="is-current">把明天安排妥当</Button></div><div className="pw-conversation-row"><Button variant="ghost">周末的小旅行</Button></div></div></section>
   </div>
   <footer className="pw-navigation-footer"><Accordion className="pw-navigation-tools"><AccordionItem id="tools" title="更多工具" icon={RiToolsLine}><WorkSidebar items={tools} selected={selected} onNavigate={setSelected} label="更多工具" hideGroupLabels compact={compact}/><Button variant="ghost" leadingIcon={RiFolderLine} title="文件与成果">文件与成果</Button></AccordionItem></Accordion><WorkSidebar items={[{key:'settings',label:'设置',icon:RiSettings4Line}]} selected={selected} onNavigate={setSelected} label="设置" hideGroupLabels compact={compact}/></footer>
  </aside>
  <div className="pw-main-workspace" data-testid="persistent-workspace">
   <header className="pw-app-header"><div className="pw-current-work"><h1>把明天安排妥当</h1></div></header>
   <main className="pw-chat-workspace" style={{display:'flex',flex:1,flexDirection:'column',minHeight:0}}>
    <div className="pw-conversation-scroll" style={{flex:1,overflow:'auto'}}><div className="pw-message-stream" style={{display:'flex',flexDirection:'column'}}>
     <article className="pw-message pw-message-user"><div className="pw-user-text">先帮我梳理一下明天要做的事。</div></article>
     <article className="pw-message pw-message-assistant"><header>PocketWorld</header><div className="afa-answerbody"><p>我们先把已经确定的事放在一起，再给还没决定的部分留一点余地。</p><p>时间、资料和要带的东西各自记清楚，就可以从容地一件件完成。</p></div></article>
    </div></div>
    <div className="pw-composer-region"><div className="pw-composer-host"><div className="afa-composer"><div className="afa-card"><textarea aria-label="持续编辑的草稿" value={draft} onChange={event=>setDraft(event.target.value)} style={{width:'100%',minHeight:88,resize:'none',background:'transparent',border:0,color:'inherit',font:'inherit'}}/><div className="afa-toolbar"><Button variant="secondary">添加资料</Button></div></div></div></div></div>
   </main>
  </div>
 </div>;
}
createRoot(document.getElementById('root')!).render(<MotionConfig reducedMotion="user"><Fixture/></MotionConfig>);
