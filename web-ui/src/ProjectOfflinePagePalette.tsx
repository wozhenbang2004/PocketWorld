import {useState} from 'react';
import {Accordion,AccordionItem,Button,RadioOptions} from './forms-library';

// Exact stock v2 stylesheet and the two append-only, code-owned literals in PortableOfflinePage.
// Never normalize custom CSS or recover a palette by parsing a piece of a stylesheet.
const systemStylesheet=`:root { color-scheme: light dark; font: 16px system-ui, sans-serif; }
body { margin: 0; min-height: 100vh; display: grid; place-items: center; }
main { box-sizing: border-box; width: min(100%, 30rem); padding: 2rem; }
h1 { font-size: clamp(1.8rem, 8vw, 2.6rem); line-height: 1.15; margin: 0; }
p { line-height: 1.6; }
button { min-height: 44px; padding: 0.6rem 1rem; font: inherit; border-radius: 0.8rem; cursor: pointer; }
`;
const paperSuffix=`:root { color-scheme: light; }
body { background-color: #f4f1ec; color: #211f1b; }
button { background-color: #302b24; color: #fffaf1; border: 1px solid #302b24; }
button:focus-visible { outline: 2px solid #44392e; outline-offset: 3px; }
`;
const inkSuffix=`:root { color-scheme: dark; }
body { background-color: #121417; color: #f3f4f6; }
button { background-color: #e6ebf4; color: #171a20; border: 1px solid #e6ebf4; }
button:focus-visible { outline: 2px solid #ebeff5; outline-offset: 3px; }
`;
const palettes=[
  {id:'system',label:'跟随系统',description:'保留原来的浅色或深色外观',stylesheet:systemStylesheet},
  {id:'paper',label:'纸白',description:'暖白背景，深色文字',stylesheet:systemStylesheet+paperSuffix,backgroundColor:'#f4f1ec',color:'#211f1b'},
  {id:'ink',label:'夜墨',description:'近黑背景，浅色文字',stylesheet:systemStylesheet+inkSuffix,backgroundColor:'#121417',color:'#f3f4f6'},
] as const;
export type OfflinePagePaletteId=typeof palettes[number]['id'];
export const offlinePagePaletteFor=(stylesheet:string)=>palettes.find(preset=>preset.stylesheet===stylesheet)?.id;
export const offlinePagePaletteStylesheet=(choice:OfflinePagePaletteId)=>palettes.find(preset=>preset.id===choice)?.stylesheet;

export function ProjectOfflinePagePalette({saved,busy,sync}:{
  saved:OfflinePagePaletteId;busy:boolean;sync:(choice:OfflinePagePaletteId)=>boolean;
}){
  const [choice,setChoice]=useState(saved),[declined,setDeclined]=useState(false);
  const preset=palettes.find(item=>item.id===choice);
  return <Accordion variant="divided" className="pw-compact-disclosures">
    <AccordionItem id="offline-page-palette" title="网页配色">
      <div className="pw-form-flow">
        <RadioOptions label="选择这页的配色" options={palettes.map(({id,label,description})=>({id,label,description}))}
          value={choice} isDisabled={busy} onChange={value=>{
            const selected=palettes.find(item=>item.id===value);
            if(selected){setDeclined(false);setChoice(selected.id)}
          }}/>
        {preset&&'backgroundColor' in preset&&<p className="px-3 py-2 rounded-lg" aria-label={preset.label+'配色示例'}
          style={{backgroundColor:preset.backgroundColor,color:preset.color}}>网页文字</p>}
        <p className="pw-field-hint">同步只改样式草稿。保存后可回 index.html 预览网页。</p>
        {declined&&<p role="alert">未同步。请核对原样式文件，已有编辑内容不变。</p>}
        <Button variant="secondary" disabled={busy||choice===saved} onClick={()=>{if(!sync(choice))setDeclined(true)}}>
          同步到编辑草稿
        </Button>
      </div>
    </AccordionItem>
  </Accordion>;
}
