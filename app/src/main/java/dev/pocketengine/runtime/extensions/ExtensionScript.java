package dev.pocketengine.runtime.extensions;

import java.nio.charset.StandardCharsets;

/** Independently authored, data-only JSON bridge. No host callback is installed in JavaScript. */
final class ExtensionScript {
    static final String INPUT="extension-request.json";
    private ExtensionScript() { }
    static byte[] compile(ExtensionProgram program)throws Exception {
        String before="""
                (async()=>{
                  'use strict';
                  let definition;
                  const defineExtension=(value)=>{
                    if(definition!==undefined||!value||typeof value!=='object'||Array.isArray(value))throw new Error('invalid extension definition');
                    for(const key of Object.keys(value))if(!['tools','context','beforeTool','onEvent'].includes(key))throw new Error('unsupported extension member');
                    definition=value;
                  };
                  (function(defineExtension){
                """;
        String after="""
                  \n})(defineExtension);
                  if(!definition)throw new Error('defineExtension must be called once');
                  const request=JSON.parse(pocket.resources['extension-request.json']);
                  const tools=definition.tools===undefined?[]:definition.tools;
                  if(!Array.isArray(tools)||tools.length>12)throw new Error('invalid tools');
                  const descriptor={tools:tools.map(tool=>{
                    if(!tool||typeof tool!=='object'||typeof tool.execute!=='function')throw new Error('invalid tool');
                    for(const key of Object.keys(tool))if(!['name','description','parameters','execute'].includes(key))throw new Error('unsupported tool member');
                    return {name:tool.name,description:tool.description,parameters:tool.parameters};
                  }),hooks:{context:typeof definition.context==='function',beforeTool:typeof definition.beforeTool==='function',onEvent:typeof definition.onEvent==='function'}};
                  for(const hook of ['context','beforeTool','onEvent'])if(definition[hook]!==undefined&&typeof definition[hook]!=='function')throw new Error('invalid hook');
                  let result={};
                  const state=request.state;
                  if(request.method==='tool'){
                    const tool=tools.find(candidate=>candidate.name===request.tool);
                    if(!tool)throw new Error('unknown tool');
                    result=await tool.execute(request.input,state);
                  }else if(request.method==='context'&&descriptor.hooks.context){result=await definition.context(request.input,state);}
                  else if(request.method==='beforeTool'&&descriptor.hooks.beforeTool){result=await definition.beforeTool(request.input,state);}
                  else if(request.method==='event'&&descriptor.hooks.onEvent){result=await definition.onEvent(request.input,state);}
                  else if(request.method!=='describe')throw new Error('unsupported invocation');
                  if(!result||typeof result!=='object'||Array.isArray(result))throw new Error('hook must return an object');
                  return JSON.stringify({descriptor,result});
                })()
                """;
        return (before+program.sourceText()+after).getBytes(StandardCharsets.UTF_8);
    }
}
