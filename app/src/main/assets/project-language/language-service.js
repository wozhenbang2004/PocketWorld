/* PocketWorld's read-only virtual-files adapter over unmodified TypeScript 5.9.3.
 * No project plugin, package loader, require, filesystem or user JS evaluation. */
(function (global) {
  'use strict';
  const VERSION = 'pocketworld.typescript-language.v1';
  const MAX_ROWS = 100, MAX_EDITS = 1000;
  function fail(message) { throw new Error(message); }
  function ordinary(path) {
    if (typeof path !== 'string' || !path || path.length > 240 || path[0] === '/' || path.includes('\\') || path.includes(':') || /[\u0000-\u001f]/.test(path)) fail('Invalid native project path');
    if (path.split('/').some(p => !p || p === '.' || p === '..' || p === '.git' || p === 'node_modules')) fail('Path leaves the selected source set');
    return path;
  }
  function prepare(input) {
    if (!input || input.format !== VERSION || !Array.isArray(input.files) || input.files.length > 128) fail('Invalid original language snapshot');
    const files = Object.create(null), refs = Object.create(null);let size = 0;
    for (const file of input.files) {
      const path = '/project/' + ordinary(file.path);
      if (typeof file.content !== 'string' || typeof file.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(file.sha256) || files[path] !== undefined) fail('Missing or duplicate original source identity');
      if ((size += file.content.length) > 1048576) fail('Language snapshot exceeds one MiB of characters');
      files[path] = file.content; refs[path] = file;
    }
    for (const name of Object.keys(global.pwLanguageLibraries)) {
      if (!/^lib\.[a-z0-9.]+\.d\.ts$/.test(name)) fail('Invalid bundled TypeScript library');
      files['/lib/' + name] = global.pwLanguageLibraries[name];
    }
    const selected = '/project/' + ordinary(input.path);
    if (files[selected] === undefined || !/\.(?:[cm]?[jt]sx?)$/.test(selected)) fail('This language profile supports JavaScript/TypeScript source files');
    if (!Number.isInteger(input.position) || input.position < 0 || input.position > files[selected].length) fail('Position is outside the original document');
    const options = {target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext,moduleResolution:ts.ModuleResolutionKind.Bundler,allowJs:true,checkJs:true,strict:true,noEmit:true,jsx:ts.JsxEmit.Preserve,skipLibCheck:true,allowNonTsExtensions:false,resolveJsonModule:true};
    const snapshots = Object.create(null);
    const host = {
      getScriptFileNames:() => Object.keys(refs).filter(name=>!name.endsWith('.json')),
      getScriptKind:name => name.endsWith('.json')?ts.ScriptKind.JSON:ts.getScriptKindFromFileName(name),
      getScriptVersion:name => refs[name] ? refs[name].sha256 : 'bundled-typescript-5.9.3',
      getScriptSnapshot:name => files[name] === undefined ? undefined : (snapshots[name] || (snapshots[name] = ts.ScriptSnapshot.fromString(files[name]))),
      getCompilationSettings:() => options,getCurrentDirectory:() => '/project',getDefaultLibFileName:() => '/lib/lib.es2022.full.d.ts',getDefaultLibLocation:() => '/lib',
      fileExists:name => files[name] !== undefined,readFile:name => files[name],
      directoryExists:name => Object.keys(files).some(file => file.startsWith(name.replace(/\/$/,'')+'/')),
      getDirectories:name => {const prefix=name.replace(/\/$/,'')+'/';return [...new Set(Object.keys(files).filter(file=>file.startsWith(prefix)&&file.slice(prefix.length).includes('/')).map(file=>file.slice(prefix.length).split('/')[0]))];},
      readDirectory:name => Object.keys(files).filter(file => file.startsWith(name.replace(/\/$/,'')+'/')),
      useCaseSensitiveFileNames:() => true,getNewLine:() => '\n',realpath:name => name
    };
    host.resolveModuleNames=(names,containing)=>names.map(name=>ts.resolveModuleName(name,containing,options,host).resolvedModule);
    return {files,refs,selected,service:ts.createLanguageService(host,ts.createDocumentRegistry())};
  }
  function location(context,fileName,span) {
    const ref=context.refs[fileName];if(!ref || !span || !Number.isInteger(span.start) || !Number.isInteger(span.length) || span.start<0 || span.start+span.length>context.files[fileName].length) return null;
    const text=context.files[fileName];const lines=text.slice(0,span.start).split('\n');
    return {path:ref.path,sha256:ref.sha256,start:span.start,length:span.length,line:lines.length,column:lines[lines.length-1].length+1,preview:text.slice(span.start,Math.min(text.length,span.start+Math.max(span.length,120))).split('\n')[0].slice(0,180)};
  }
  function changes(context,entries) {
    if(entries.length>MAX_EDITS)fail('The complete formatting/rename edit exceeds the original 1000-edit bound');
    const result=[];for(const change of entries){const row=location(context,change.fileName,change.textSpan);if(!row)fail('An edit targets a library or source outside the original snapshot');result.push({...row,newText:change.newText});}return result;
  }
  function syntax(text,limit) {
    const scanner=ts.createScanner(ts.ScriptTarget.Latest,false,ts.LanguageVariant.JSX,text),rows=[];let token,more=false;
    while((token=scanner.scan())!==ts.SyntaxKind.EndOfFileToken){const start=scanner.getTokenPos(),length=scanner.getTextPos()-start;let kind='';if(token>=ts.SyntaxKind.FirstKeyword&&token<=ts.SyntaxKind.LastKeyword)kind='keyword';else if(token===ts.SyntaxKind.StringLiteral||token===ts.SyntaxKind.NoSubstitutionTemplateLiteral||token===ts.SyntaxKind.TemplateHead||token===ts.SyntaxKind.TemplateMiddle||token===ts.SyntaxKind.TemplateTail)kind='string';else if(token===ts.SyntaxKind.NumericLiteral||token===ts.SyntaxKind.BigIntLiteral)kind='number';else if(token===ts.SyntaxKind.SingleLineCommentTrivia||token===ts.SyntaxKind.MultiLineCommentTrivia)kind='comment';if(kind&&length){if(rows.length>=limit){more=true;break;}rows.push({start,length,kind});}}
    return {rows,truncated:more};
  }
  function run(input) {
    const context=prepare(input),service=context.service,file=context.selected;let rows=[],more=false;
    try {
      switch(input.action) {
        case 'diagnostics': {
          const values=[...service.getSyntacticDiagnostics(file),...service.getSemanticDiagnostics(file)];
          for(const diagnostic of values){if(rows.length>=MAX_ROWS){more=true;break;}const row=location(context,diagnostic.file?diagnostic.file.fileName:file,{start:diagnostic.start||0,length:diagnostic.length||0});if(row)rows.push({...row,code:diagnostic.code,category:ts.DiagnosticCategory[diagnostic.category],message:ts.flattenDiagnosticMessageText(diagnostic.messageText,'\n').slice(0,1600)});}break;
        }
        case 'complete': {
          const result=service.getCompletionsAtPosition(file,input.position,{includeCompletionsForModuleExports:false,includeCompletionsWithInsertText:true});
          if(result){
            const source=service.getProgram().getSourceFile(file),token=ts.getTokenAtPosition(source,Math.max(0,input.position-1));
            const identifier=token&&token.kind===ts.SyntaxKind.Identifier&&token.getStart(source)<=input.position&&token.end>=input.position;
            const word=identifier?{start:token.getStart(source),length:token.end-token.getStart(source)}:{start:input.position,length:0};
            const prefix=context.files[file].slice(word.start,input.position).toLocaleLowerCase('en-US');
            const entries=result.entries.filter(entry=>!prefix||entry.name.toLocaleLowerCase('en-US').startsWith(prefix)).sort((a,b)=>a.sortText<b.sortText?-1:a.sortText>b.sortText?1:a.name.localeCompare(b.name));
            more=entries.length>50;rows=entries.slice(0,50).map(entry=>({name:entry.name,kind:entry.kind,sortText:entry.sortText,insertText:entry.insertText||entry.name,replacementSpan:entry.replacementSpan||result.optionalReplacementSpan||word}));
          }break;
        }
        case 'definition': {
          for(const entry of service.getDefinitionAtPosition(file,input.position)||[]){const row=location(context,entry.fileName,entry.textSpan);if(row)rows.push({...row,name:entry.name,kind:entry.kind});else if(entry.fileName.startsWith('/lib/'))rows.push({library:true,path:entry.fileName.slice(5),name:entry.name,kind:entry.kind,editable:false});if(rows.length>=MAX_ROWS){more=true;break;}}break;
        }
        case 'references': {
          for(const entry of service.getReferencesAtPosition(file,input.position)||[]){const row=location(context,entry.fileName,entry.textSpan);if(row)rows.push({...row,isDefinition:!!entry.isDefinition,isWriteAccess:!!entry.isWriteAccess});if(rows.length>=MAX_ROWS){more=true;break;}}break;
        }
        case 'rename': {
          const name=input.newName;if(typeof name!=='string'||name.length>100||!ts.isIdentifierText(name,ts.ScriptTarget.Latest)||ts.stringToToken(name)!==undefined)fail('Choose a non-keyword TypeScript identifier');
          const info=service.getRenameInfo(file,input.position,{allowRenameOfImportPath:false});if(!info.canRename)fail(info.localizedErrorMessage||'This original symbol cannot be renamed');
          const edits=(service.findRenameLocations(file,input.position,false,false,true)||[]).map(entry=>({...entry,newText:(entry.prefixText||'')+name+(entry.suffixText||'')}));rows=changes(context,edits);break;
        }
        case 'format': rows=changes(context,service.getFormattingEditsForDocument(file,{indentSize:2,tabSize:2,convertTabsToSpaces:true,newLineCharacter:'\n',insertSpaceAfterCommaDelimiter:true,insertSpaceAfterKeywordsInControlFlowStatements:true}).map(entry=>({fileName:file,textSpan:entry.span,newText:entry.newText})));break;
        case 'classify': {const actual=syntax(context.files[file],4000);rows=actual.rows;more=actual.truncated;break;}
        default: fail('Unsupported language action');
      }
      const answer={format:VERSION,compilerVersion:ts.version,action:input.action,snapshotIdentity:input.snapshotIdentity,path:input.path,sha256:context.refs[file].sha256,position:input.position,rows,truncated:more,sourceFiles:Object.keys(context.refs).length,userCodeExecuted:false,projectPluginsLoaded:false};
      if(input.action==='diagnostics'){const actual=syntax(context.files[file],1200);answer.syntax=actual.rows;answer.syntaxTruncated=actual.truncated;}
      const output=JSON.stringify(answer);if(output.length>131072)fail('The complete language result exceeds its bounded native result size; narrow the request');return output;
    } finally {service.dispose();}
  }
  global.pwProjectLanguage={run};
})(globalThis);
