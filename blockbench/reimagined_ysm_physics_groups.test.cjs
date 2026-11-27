'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const api = require('./reimagined_ysm_physics_groups.js');
const cosmicSource=api.cosmicRegistrationSource('crosses','cross.png\n layers/star.png\n');
assert.ok(cosmicSource.includes('event.register("crosses", {"textures":["cross.png","layers/star.png"]})'));
assert.equal(api.scriptAnalysis(cosmicSource,[]).diagnostics.length,0);
for(const [name,textures] of [['','a.png'],['../sky','a.png'],['sky:bad','a.png'],['sky',''],['sky','../a.png'],['sky','/a.png'],['sky','a.jpg'],['sky','C:\\a.png'],['sky',Array(65).fill('a.png').join('\n')]])assert.throws(()=>api.cosmicRegistrationSource(name,textures));

class Group {
    static all=[];
    constructor(data={}) {Object.assign(this,{uuid:String(Group.all.length),name:'bone',origin:[0,0,0],parent:'root',children:[],export:true,locked:false,rysm_properties:{}},data);}
    addTo(parent){if(this.parent instanceof Group)this.parent.children=this.parent.children.filter(child=>child!==this);this.parent=parent;if(parent instanceof Group)parent.children.push(this);return this;}
    init(){Group.all.push(this);return this;}
    remove(){Group.all=Group.all.filter(g=>g!==this);if(this.parent instanceof Group)this.parent.children=this.parent.children.filter(c=>c!==this);}
}
class Cube {constructor(){this.export=true;this.faces={front:{texture:'texture'},back:{texture:null}};}}
Object.assign(global,{Group,Cube,Project:{},Canvas:{updateAllBones(){}},Animation:{all:[]},Blockbench:{showQuickMessage(){},export(){}}});
const snapshots=[];let after;
const snapshot=()=>Group.all.map(group=>({group,name:group.name,parent:group.parent,origin:group.origin.slice(),properties:structuredClone(api.props(group)),owned:group.rysm_script_owned}));
function restore(values){Group.all=values.map(v=>v.group);for(const v of values){Object.assign(v.group,{name:v.name,parent:v.parent,origin:v.origin.slice(),rysm_properties:structuredClone(v.properties),rysm_script_owned:v.owned,children:[]});}for(const v of values)if(v.parent instanceof Group)v.parent.children.push(v.group);}
global.Undo={initEdit(){snapshots.push(snapshot());},finishEdit(){after=snapshot();}};
const cape=new Group({name:'cape',rysm_properties:{cosmic:{sc:'3388FF'},outline:{width:2}}}).init();
api.createPhysics(cape,'tail');
assert.equal(cape.name,'cape');assert.equal(api.physicsKind(cape),'tail');
assert.equal(api.props(cape).cosmic.sc,'3388FF');assert.equal(cape.children.length,1);assert.equal(api.props(cape.children[0]).end,true);
assert.equal(api.effectivePhysics(cape).gw,3);assert.equal(api.effectivePhysics(cape).a,50);
const created=after;restore(snapshots.at(-1));assert.equal(api.isPhysics(cape),false);assert.equal(cape.children.length,0);
restore(created);assert.equal(api.isPhysics(cape),true);assert.equal(cape.children.length,1);
const child=new Group({name:'tip',origin:[0,-2,0]}).addTo(cape).init();
api.createPhysics(child,'tail');assert.equal(cape.children.filter(g=>api.props(g).end).length,0);assert.equal(child.children.filter(g=>api.props(g).end).length,1);
api.setProperties(cape,{physics:{kind:'tail',gw:5,a:40},cosmic:{sc:'3388FF'},outline:{width:2},facingCamera:true});
assert.equal(api.effectivePhysics(child).gw,5);
api.setProperties(child,{physics:{g:0}});assert.equal(api.effectivePhysics(child).g,0);assert.equal(api.effectivePhysics(child).gw,undefined);
let topology=api.physicsTopology(Group.all);assert.equal(topology.segments.length,2);assert.ok(topology.segments.every(s=>s.radius===1));
const collider=new Group({name:'body',rysm_properties:{collider:true}}).init(),cube=new Cube();collider.children.push(cube);
assert.equal(api.physicsTopology(Group.all).cubes.length,1);
api.removePhysics(child);assert.equal(api.props(cape).physics.gw,5);assert.ok(cape.children.some(c=>api.props(c).end));assert.equal(api.isPhysics(child),false);
Group.all=[collider];assert.equal(api.physicsTopology(Group.all).cubes.length,1,'Collider volumes must work without a bone chain');
assert.deepEqual(api.inheritValues({g:9.8,a:90},{gw:0}),{a:90,gw:0});
assert.throws(()=>api.validateProperties({physics:{g:0,gw:0}}));
assert.throws(()=>api.validateProperties({physics:{},collider:true}));
assert.throws(()=>api.validateProperties({outline:{color:'#FFFFFF'}}));
assert.throws(()=>api.validateProperties({frameAnimation:{count:2,tick:0}}));
assert.doesNotThrow(()=>api.validateProperties({physics:{g:0,bc:false},cosmic:false,outline:{color:'auto',width:0},frameAnimation:{count:2,macro:'rysmjs.frame',macroRepeat:false}}));
assert.equal(api.sectorLines(180).length,4*16*6);assert.ok(api.capsuleLines(5,1).length>0);

const chainRoot=new Group({name:'root',rysm_properties:{physics:{g:2},glow:true}}).init();
const chainChild=new Group({name:'child',rysm_properties:{physics:{r:3}}}).addTo(chainRoot).init();
const chainEnd=new Group({name:'end',rysm_properties:{end:true}}).addTo(chainChild).init();
assert.deepEqual(api.physicsChain(chainChild),[chainRoot,chainChild]);assert.deepEqual(api.physicsChain(chainEnd),[chainRoot,chainChild]);
api.applyChainProperties([chainRoot,chainChild],[{g:5,a:30},{g:5,a:45}]);assert.equal(api.props(chainRoot).glow,true);assert.equal(api.props(chainChild).physics.a,45);
restore(snapshots.at(-1));assert.equal(api.props(chainRoot).physics.g,2);assert.equal(api.props(chainChild).physics.r,3);
chainChild.locked=true;assert.throws(()=>api.applyChainProperties([chainRoot,chainChild],[{},{}]));chainChild.locked=false;

const lower=new Group({name:'bow'}),upper=new Group({name:'Bow2'}),ornament=new Group({name:'child'});Group.all=[lower,upper,ornament];
assert.equal(api.restoreImportedGroupNames(Group.all,[{name:'bow'},{name:'Bow'},{name:'child',parent:'Bow'}]),true);
assert.equal(upper.name,'Bow');assert.equal(ornament.parent,upper);assert.equal(lower.parent,'root');
assert.equal(api.restoreImportedGroupNames(Group.all,[{name:'x'},{name:'x'},{name:'child'}]),false);
const text=api.scriptSource([['sky',{cosmic:{sc:'3388FF'},outline:{width:2}}]]);
assert.ok(text.includes('由 Blockbench 自动生成'));assert.ok(text.includes('advanced logic'));assert.ok(text.includes("Rysm.model('group_modifier'"));
assert.deepEqual(api.generatedGroups(text),[['sky',{cosmic:{sc:'3388FF'},outline:{width:2}}]]);
assert.deepEqual(api.generatedGroups('// Generated by the RYSM Blockbench editor.\nRysm.group("cape", {physics:{g:-2}});'),[['cape',{physics:{g:-2}}]]);
for(const source of [text+'\nwhile(true){}',text+'\nglobalThis.wasExecuted=true;',text.replace('"3388FF"','danger()')])assert.throws(()=>api.generatedGroups(source));
assert.equal(global.wasExecuted,undefined);
for(const name of ['scripts/../x.js','scripts/generated.main.js','scripts/author/generated.main.js','scripts/blockbench.js','scripts/migrated.js'])assert.throws(()=>api.scriptPath(name));
assert.equal(api.scriptPath('scripts/author/main.js'),'scripts/author/main.js');
assert.ok(api.readOnlyScript(api.generatedFile));

let hints=api.scriptCompletions("Rysm.model('group_modifier', evt => { evt.col",44,['body']);
assert.ok(hints.items.some(item=>item.text==='evt.collider()'));
const partial="Rysm.model('tick', state => { state.gr";
assert.deepEqual(api.eventContext(partial,partial.length),{type:'tick',parameter:'state'});
assert.ok(!api.scriptCompletions(partial,partial.length).items.some(item=>item.text.includes('.group(')));
const registration="Rysm.model('cosmic', e => { e.reg";
hints=api.scriptCompletions(registration,registration.length);assert.ok(hints.items.some(item=>item.text.includes('textures')));assert.ok(!hints.items.some(item=>item.text.includes('fallback')));
const named="Rysm.model('group_modifier', event => {event.modify('bo";
assert.deepEqual(api.scriptCompletions(named,named.length,['body','cape']).items.map(i=>i.text),['body']);
const valid="Rysm.model('group_modifier', e => { e.modify('sky').outline({color:'33AAFF',width:2}); });\nRysm.model('pose', g=>g.group('sky').rotate(0,1,0));";
let analysis=api.scriptAnalysis(valid,['sky']);assert.deepEqual(analysis.diagnostics,[]);assert.equal(analysis.colors[0].color,'33AAFF');
assert.ok(api.scriptAnalysis("Rysm.model('tick', g=>g.group('sky'));",['sky']).diagnostics.some(d=>d.message.includes('pose')));
assert.equal(api.scriptAnalysis("Rysm.model('pose', g=> {",[]).diagnostics[0].severity,'error');
assert.ok(api.scriptAnalysis("Rysm.group('x', {});").diagnostics.some(d=>d.message.includes('新版')));
assert.ok(api.scriptAnalysis('globalThis.wasExecuted = true; while(true){};').diagnostics.length===0);assert.equal(global.wasExecuted,undefined);
assert.ok(api.scriptAnalysis("Rysm.model('molang', e=>e.register('rysmjs.bad',g=>1,{sync:1}));").diagnostics.length>=2);
assert.ok(api.scriptAnalysis("Rysm.model('group_modifier', e=>e.modify('x').outline({width:65}));",['x']).diagnostics.some(d=>d.message.includes('有效范围')));
assert.ok(api.parameterHint("Rysm.model('pose',g=>g.group('sky').rotate(",50).includes('度数'));

const folder=fs.mkdtempSync(path.join(os.tmpdir(),'rysm-bb-save-'));
try {
    const scripts=path.join(folder,'rysm/scripts');fs.mkdirSync(scripts,{recursive:true});fs.writeFileSync(path.join(folder,'ysm.json'),'{}');
    global.Project={save_path:path.join(folder,'model.bbmodel')};Group.all=[new Group({name:'cape',rysm_script_owned:true,rysm_properties:{physics:{g:3},outline:{width:2}}})];
    const manifest=path.join(folder,'rysm/manifest.json'),old=path.join(scripts,'blockbench.js'),target=path.join(scripts,'generated.blockbench.js'),manual=path.join(scripts,'main.js');
    const oldSource='// Generated by the RYSM Blockbench editor.\nRysm.model("group_modifier", event=>{event.modify("cape").physics({g:2});event.modify("other").glow();});\n';
    fs.writeFileSync(old,oldSource);fs.writeFileSync(manual,'// 高级脚本：保留\n');fs.writeFileSync(manifest,JSON.stringify({version:1,scripts:['scripts/main.js','scripts/blockbench.js','scripts/after.js']}));
    api.writeScripts();
    assert.deepEqual(JSON.parse(fs.readFileSync(manifest)).scripts,['scripts/main.js',api.generatedFile,'scripts/after.js']);
    assert.equal(fs.existsSync(old),false);assert.ok(fs.readdirSync(scripts).some(name=>name.startsWith('blockbench.js.')&&name.endsWith('.bak')));
    assert.equal(fs.readFileSync(manual,'utf8'),'// 高级脚本：保留\n');
    let entries=api.generatedGroups(fs.readFileSync(target,'utf8'));assert.equal(entries.find(([n])=>n==='cape')[1].physics.g,3);assert.equal(entries.find(([n])=>n==='other')[1].glow,true);
    const first=fs.readFileSync(target,'utf8');api.writeScripts();assert.equal(fs.readFileSync(target,'utf8'),first);
    global.Format={id:'bedrock'};
    Group.all[0].rysm_properties.physics={r:16,air:4,a:45};
    for(const event of [{project:Project,saved:false},{project:{},saved:true},{model:{},options:{backup:true}}])api.saveScripts(event);
    assert.equal(fs.readFileSync(target,'utf8'),first,'Edits, other projects and backup compilation must not write scripts');
    const lstat=fs.lstatSync;
    try{fs.lstatSync=undefined;api.saveScripts({project:Project,saved:true});}
    finally{fs.lstatSync=lstat;}
    assert.deepEqual(api.generatedGroups(fs.readFileSync(target,'utf8')).find(([n])=>n==='cape')[1].physics,{r:16,air:4,a:45},'Native model save must persist current form values');
    Group.all[0].rysm_properties.physics={g:3};api.writeScripts();
    fs.writeFileSync(old,oldSource+'\nthrow Error("manual");');const beforeManifest=fs.readFileSync(manifest,'utf8');
    assert.throws(()=>api.writeScripts());assert.equal(fs.readFileSync(target,'utf8'),first);assert.equal(fs.readFileSync(manifest,'utf8'),beforeManifest);
    fs.unlinkSync(target);assert.throws(()=>api.writeScripts(),/手写逻辑/);assert.ok(fs.readFileSync(old,'utf8').includes('manual'));assert.equal(fs.existsSync(target),false);
    fs.writeFileSync(old,oldSource);api.writeScripts();
    const migrated=path.join(scripts,'prefix-migrated.js');
    fs.writeFileSync(migrated,'// Automatically migrated from group-name properties.\nRysm.model("group_modifier", e=>e.modify("cape").physics({g:7}));\nRysm.model("group_modifier", e=>e.modify("Bow").facingCamera(true));');
    fs.writeFileSync(manifest,JSON.stringify({version:1,scripts:['scripts/main.js','scripts/prefix-migrated.js']}));
    Project={export_path:path.join(folder,'models/main.json'),save_path:''};
    Group.all=[new Group({name:'cape'})];
    let state=api.syncScriptGroups();assert.equal(api.props(Group.all[0]).physics.g,7);assert.deepEqual(state.missing,['Bow']);
    Group.all[0].rysm_properties={physics:{g:9}};api.syncScriptGroups();assert.equal(api.props(Group.all[0]).physics.g,9);
    api.writeScripts();
    assert.deepEqual(JSON.parse(fs.readFileSync(manifest)).scripts,['scripts/main.js',api.generatedFile,'scripts/prefix-migrated.js']);
    assert.deepEqual(api.generatedGroups(fs.readFileSync(migrated,'utf8')).map(([name])=>name),['Bow']);
    assert.ok(api.readOnlyScript('scripts/custom.js',fs.readFileSync(migrated,'utf8')));
    fs.mkdirSync(path.join(folder,'models'),{recursive:true});
    fs.writeFileSync(path.join(folder,'ysm.json'),JSON.stringify({files:{player:{model:{main:'models/main.json',arm:'models/arm.json'}},projectiles:[{model:'models/arrow.json'}]}}));
    fs.writeFileSync(path.join(folder,'models/arrow.json'),JSON.stringify({'minecraft:geometry':[{bones:[{name:'Bow'}]}]}));
    fs.writeFileSync(migrated,'// Automatically migrated from group-name properties.\nRysm.model("group_modifier",e=>e.modify("arrow.Bow").facingCamera());');
    Project={export_path:path.join(folder,'models/arrow.json')};Group.all=[new Group({name:'Bow'})];
    assert.equal(api.modelScope(),'arrow');api.syncScriptGroups();assert.equal(api.props(Group.all[0]).facingCamera,true);
    assert.deepEqual(api.projectEntries(),[['arrow.Bow',{facingCamera:true}]]);assert.ok(api.availableGroupNames().includes('arrow.Bow'));
    Project={save_path:path.join(folder,'models/arrow.bbmodel')};assert.equal(api.modelScope(),'arrow');
    Project={export_path:path.join(folder,'models/arrow.json')};
    api.writeScripts();assert.ok(api.generatedGroups(fs.readFileSync(target,'utf8')).some(([name])=>name==='arrow.Bow'));
    api.saveUserScript('scripts/main.js','// 新脚本\n',fs.readFileSync(manual,'utf8'));assert.equal(fs.readFileSync(manual,'utf8'),'// 新脚本\n');
    assert.throws(()=>api.saveUserScript('scripts/main.js','overwrite','stale'),/外部修改/);assert.equal(fs.readFileSync(manual,'utf8'),'// 新脚本\n');
    assert.throws(()=>api.saveUserScript('scripts/new.js','中'.repeat(400000)),/1 MiB/);assert.equal(fs.existsSync(path.join(scripts,'new.js')),false);
    const rename=fs.renameSync;let count=0;
    try{fs.renameSync=(...args)=>{if(++count===2)throw Error('injected write failure');return rename(...args);};assert.throws(()=>api.writeScriptTransaction(new Map([[manual,'replacement'],[path.join(scripts,'new.js'),'new']])));}
    finally{fs.renameSync=rename;}
    assert.equal(fs.readFileSync(manual,'utf8'),'// 新脚本\n');assert.equal(fs.existsSync(path.join(scripts,'new.js')),false);
    assert.ok(!fs.readdirSync(scripts).some(name=>name.endsWith('.tmp')));
}finally{fs.rmSync(folder,{recursive:true,force:true});}
const source=fs.readFileSync(path.join(__dirname,'src/plugin.js'),'utf8');
for(const removed of ['scriptName','setScriptName','importLegacyGroups','startScriptPreview','previewWorker','cosmicPreview','sanitizeName','ysmPhys@'])assert.ok(!source.includes(removed),removed+' must be removed');
console.log('RYSM plugin static, properties, undo/redo, migration and file checks passed');

if(process.argv.includes('--browser')) {
    const folder=fs.mkdtempSync(path.join(os.tmpdir(),'rysm-editor-'));
    fs.copyFileSync(path.join(__dirname,'reimagined_ysm_physics_groups.js'),path.join(folder,'plugin.js'));
    fs.writeFileSync(path.join(folder,'index.html'),`<!doctype html><meta charset="utf-8"><pre id="result">RUNNING</pre><script>
window.onerror=(message,file,line)=>{document.querySelector('#result').textContent='ERROR '+message+' '+file+':'+line;};
var module={exports:{}},Project={},Group={all:[]},Cube={all:[]};
</script><script src="plugin.js"></script><script>
(async()=>{
const api=module.exports,host=document.createElement('div');document.body.append(host);
const assert=(value,message)=>{if(!value)throw Error(message);};
const editor=api.attachScriptEditor(host,"Rysm.model('group_modifier', e=>{e.modify('sky').outline({color:'33AAFF',width:2});});",{groups:['sky']});
assert(host.querySelector('.CodeMirror'),'CodeMirror missing');assert(host.querySelector('.CodeMirror-linenumbers'),'Line numbers missing');
assert(host.querySelector('.cm-string'),'Syntax highlighting missing');
assert(host.querySelector('.rysm-color-chip'),'Color chip missing');
const input=document.querySelector('input[type=color]');input.value='#ff4400';input.dispatchEvent(new Event('change'));editor.analyze();
assert(editor.cm.getValue().includes("color:'FF4400'"),'RGB replacement damaged the script');
assert(api.scriptAnalysis(editor.cm.getValue(),['sky']).diagnostics.length===0,'Color edit made invalid source');
editor.cm.setValue("Rysm.model('pose', g=> { g.gr");editor.cm.setCursor(editor.cm.lineCount()-1,editor.cm.getLine(0).length);editor.complete();
assert(document.querySelector('.CodeMirror-hints'),'Completion popup missing');
assert(document.querySelector('.CodeMirror-hints').textContent.includes('group'),'Contextual group completion missing');editor.cm.closeHint();
const wrapper=editor.cm.getWrapperElement();wrapper.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));editor.complete();assert(!document.querySelector('.CodeMirror-hints'),'Completion interferes with IME');wrapper.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true}));
editor.cm.setValue("// 中文输入保留\\nRysm.model('tick', g => { g.group('sky'); });");editor.analyze();
assert(editor.cm.getValue().includes('中文输入保留'),'Chinese text lost');assert(host.querySelector('.rysm-diagnostics').textContent.includes('pose'),'Line diagnostics missing');
editor.cm.setOption('readOnly',true);editor.complete();assert(!document.querySelector('.CodeMirror-hints'),'Readonly completion must not edit generated files');
editor.dispose();assert(!host.querySelector('.CodeMirror'),'Editor DOM leaked');assert(!document.querySelector('input[type=color]'),'Color picker leaked');
// Exercise file switching, dirty prompts and close protection with the documented Dialog callback contract.
const dialogs=[],messages=[];window.Project={rysm_scripts:{'scripts/main.js':'// original'}};window.Group={all:[]};
window.Blockbench={export(){},showMessageBox(options,callback){messages.push({options,callback});}};
window.Dialog=class {constructor(options){this.options=options;this.node=document.createElement('div');for(const line of options.lines||[])if(line instanceof Node)this.node.append(line);document.body.append(this.node);dialogs.push(this);}show(){this.node.hidden=false;return this;}hide(){this.node.hidden=true;}};
api.editScripts();let dialog=dialogs.at(-1),cm=dialog.node.querySelector('.CodeMirror').CodeMirror;
cm.setValue('// changed 中文');let select=dialog.node.querySelector('select');assert(select.options[0].textContent.startsWith('* '),'Dirty mark missing');
select.value=api.generatedFile;select.dispatchEvent(new Event('change'));assert(messages.length===1,'Dirty switch prompt missing');messages.pop().callback(0);
assert(cm.getOption('readOnly'),'Generated script must be readonly');select.value='scripts/main.js';select.dispatchEvent(new Event('change'));assert(cm.getValue()==='// changed 中文','Draft lost on switch');
dialog.options.onCancel();assert(!dialog.node.hidden&&messages.length===1,'Dirty close was not protected');messages.pop().callback(0);assert(!dialog.node.hidden,'Return to editor failed');
dialog.options.onCancel();messages.pop().callback(2);assert(dialog.node.hidden,'Discard and close failed');
// All physical fields are typed; chain changes commit as one undo action.
window.Undo={initEdit(){},finishEdit(){}};
const root={name:'root',uuid:'r',export:true,children:[],rysm_properties:{physics:{g:2},glow:true}},tip={name:'tip',uuid:'t',export:true,parent:null,children:[],rysm_properties:{physics:{r:3}}};tip.parent=root;root.children=[tip];Group.all=[root,tip];
api.editPhysicsChain(tip);dialog=dialogs.at(-1);
assert(!dialog.node.querySelector('textarea'),'Chain editor still requires JSON');assert(dialog.node.querySelectorAll('input[type=range]').length===3,'Per-bone angles missing');
const batch=dialog.node.querySelector('details');const angle=batch.querySelector('input[type=range]');angle.value='45';angle.dispatchEvent(new Event('input'));batch.querySelector('button').click();dialog.options.onConfirm();
assert(root.rysm_properties.physics.a===45&&tip.rysm_properties.physics.a===45,'Batch parameters not applied to entire chain');assert(root.rysm_properties.glow,'Unrelated group effects lost');dialog.hide();
api.editScriptProperties(root);dialog=dialogs.at(-1);assert(!dialog.node.querySelector('textarea'),'Group form still requires JSON');assert(dialog.node.querySelector('input[type=color]'),'Group color picker missing');dialog.hide();
window.Project={rysm_scripts:{}};Group.all=[];api.editScripts(true);dialog=dialogs.at(-1);
assert(dialog.node.querySelector('.CodeMirror'),'New-script button did not open editor');
assert(dialog.node.querySelector('select').value==='scripts/custom.js','New script was not selected');
assert(Project.rysm_script_drafts['scripts/custom.js'].includes("Rysm.model('group_modifier'"),'New script template/draft missing');
assert(messages.length===0,'New script unexpectedly prompted for unsaved default script');
dialog.options.onCancel();messages.pop().callback(2);
document.querySelector('#result').textContent='BROWSER PASS: chain forms/batch editing, script creation, highlighting, completion, IME suppression, diagnostics, RGB, readonly, drafts, close protection, cleanup';
})().catch(error=>{document.querySelector('#result').textContent='ERROR '+error.stack;});
</script>`);
    runBrowser(folder).catch(error=>{console.error(error);process.exitCode=1;});
}

async function runBrowser(folder) {
    const {spawn}=require('node:child_process'),chrome=process.env.RYSM_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
    if(!fs.existsSync(chrome))throw Error('Set RYSM_CHROME to a Chromium executable for the browser probe');
    const profile=path.join(folder,'profile');
    const browser=spawn(chrome,['--headless=new','--no-first-run','--enable-unsafe-swiftshader','--allow-file-access-from-files','--remote-debugging-port=0','--user-data-dir='+profile],{windowsHide:true,stdio:'ignore'});
    let socket;
    try {
        let port;for(let n=0;n<100;n++){try{port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];break;}catch{}await new Promise(resolve=>setTimeout(resolve,100));}
        if(!port)throw Error('Chromium did not start');
        const target=await (await fetch('http://127.0.0.1:'+port+'/json/new?'+encodeURIComponent(pathToFileURL(path.join(folder,'index.html')).href),{method:'PUT'})).json();
        socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
        let next=0;const pending=new Map();socket.onmessage=event=>{const data=JSON.parse(event.data);if(pending.has(data.id)){pending.get(data.id)(data);pending.delete(data.id);}};
        const call=(method,params={})=>new Promise(resolve=>{const id=++next;pending.set(id,resolve);socket.send(JSON.stringify({id,method,params}));});
        let text='';for(let n=0;n<120;n++) {
            const result=await call('Runtime.evaluate',{expression:'document.querySelector("#result").textContent',returnByValue:true});text=result.result?.result?.value || '';
            if(text.includes('BROWSER PASS')||text.startsWith('ERROR'))break;
            await new Promise(resolve=>setTimeout(resolve,100));
        }
        console.log(text);assert(text.includes('BROWSER PASS'),'Browser editor checks failed: '+text);
        await call('Browser.close');
    }finally{socket?.close();browser.kill();}
}
