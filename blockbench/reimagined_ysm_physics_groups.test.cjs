const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const api = require('./reimagined_ysm_physics_groups.js');

assert.deepEqual(api.generatedGroups('// Automatically migrated from group-name properties.\nRysm.group("cape", {"physics":{"g":"2"}});\n'), [['cape',{physics:{g:'2'}}]]);
assert.throws(()=>api.generatedGroups('// Automatically migrated from group-name properties.\nfetch("https://example.com");'));
assert.throws(()=>api.scriptPath('scripts/../escape.js'));
assert.throws(()=>api.scriptPath('scripts/blockbench.js'));
assert.equal(api.scriptPath('scripts/author/main.js'),'scripts/author/main.js');
assert.equal(api.animationFrame({animation:{frametime:2,frames:[0,{index:1,time:4}]}},2,3),1);
assert.equal(api.animationFrame({animation:{frametime:2,frames:[0,1]}},2,4),0);
assert.throws(()=>api.animationFrame({animation:{frames:[3]}},2,0));
assert.deepEqual(api.inputEdges(new Set(['esc']),new Set()),{esc:{down:true,pressed:true,released:false}});
assert.deepEqual(api.inputEdges(new Set(),new Set(['esc'])),{esc:{down:false,pressed:false,released:true}});
assert.equal(api.read('ysmPhys#enable=(rysmjs.toggle=1)@cape').values.enable,'rysmjs.toggle=1');

const messages=[],sandbox={};sandbox.self=sandbox;sandbox.postMessage=value=>messages.push(value);
const context=vm.createContext(sandbox);
const blobs=new Map();let nextBlob=0;
sandbox.Blob=class {constructor(parts){this.source=parts.join('');}};
sandbox.URL={createObjectURL(blob){const key='blob:probe-'+(++nextBlob);blobs.set(key,blob.source);return key;},revokeObjectURL(key){blobs.delete(key);}};
sandbox.importScripts=url=>vm.runInContext(blobs.get(url),context,{timeout:1000,filename:url});
vm.runInContext('('+api.previewWorker.toString()+')()',context,{timeout:1000});
function send(data){sandbox.message=data;vm.runInContext('onmessage({data:message})',context,{timeout:1000});return messages.at(-1);}
send({type:'load',files:{'scripts/main.js':`
Rysm.group('ornament',{facingCamera:true});
Rysm.update(ctx=>{if(ctx.input.key('key.keyboard.escape').pressed)ctx.state.toggle=!ctx.state.toggle;});
Rysm.molang('toggle',ctx=>!!ctx.state.toggle,{fallback:0,sync:true});
Rysm.molang('host',ctx=>typeof fetch==='undefined' && typeof navigator==='undefined');
Rysm.molang('bad',ctx=>NaN,{fallback:7});
Rysm.pose(ctx=>ctx.group('ornament').rotate(0,ctx.molang('rysmjs.toggle')*90,0));
`}});
assert.equal(messages.at(-1).type,'ready');
const mock={entity:{local:true},camera:{yaw:0,pitch:0},molang:{},remote:{}};
let result=send({type:'tick',mock,ticks:1,keys:{'key.keyboard.escape':{down:true,pressed:true,released:false}},bindings:{}});
assert.equal(result.values.toggle,1);assert.equal(result.values.host,1);assert.equal(result.values.bad,7);assert.equal(result.transforms[0].values[1],90);
result=send({type:'tick',mock:{...mock,entity:{local:false},remote:{toggle:0}},ticks:2,keys:{},bindings:{}});
assert.equal(result.values.toggle,0);assert.equal(result.transforms[0].values[1],0);
const modelFolder=fs.mkdtempSync(path.join(os.tmpdir(),'rysm-bb-save-'));
try {
    fs.mkdirSync(path.join(modelFolder,'rysm/scripts'),{recursive:true});fs.writeFileSync(path.join(modelFolder,'ysm.json'),'{}');
    global.Project={save_path:path.join(modelFolder,'model.bbmodel')};global.Group={all:[{name:'cape',export:true,rysm_properties:{physics:{g:3}},rysm_script_owned:true}]};
    const manifest=path.join(modelFolder,'rysm/manifest.json'),migrated=path.join(modelFolder,'rysm/scripts/migrated.js'),manual=path.join(modelFolder,'rysm/scripts/main.js');
    fs.writeFileSync(manifest,JSON.stringify({version:1,scripts:['scripts/migrated.js','scripts/main.js']}));
    fs.writeFileSync(migrated,'// Automatically migrated from group-name properties.\nRysm.group("cape", {"physics":{"g":2}});\nRysm.group("attachment", {"glow":true});\n');
    fs.writeFileSync(manual,'// user code: keep me\n');api.writeScripts();
    assert.deepEqual(api.generatedGroups(fs.readFileSync(migrated,'utf8')),[['attachment',{glow:true}]]);
    assert(JSON.parse(fs.readFileSync(manifest)).scripts.includes('scripts/migrated.js'));assert.equal(fs.readFileSync(manual,'utf8'),'// user code: keep me\n');
    global.Group.all[0].rysm_properties={};api.writeScripts();assert(api.scriptSource().includes('Rysm.group("cape", {});'));
    const group={name:'cape',rysm_properties:{physics:{g:2},cosmic:{effect:'sky'}}};global.Group.all=[group];api.setScriptName(group,'cape');assert.deepEqual(group.rysm_properties,{cosmic:{effect:'sky'}});
    const before=fs.readFileSync(manifest,'utf8'),rename=fs.renameSync;let injected=false;
    fs.renameSync=(from,to)=>{if(!injected&&to.endsWith('manifest.json')){injected=true;throw Error('Injected publication failure');}return rename(from,to);};
    try{assert.throws(()=>api.saveUserScript('scripts/main.js','changed'),/Injected publication failure/);}finally{fs.renameSync=rename;}
    assert.equal(fs.readFileSync(manual,'utf8'),'// user code: keep me\n');assert.equal(fs.readFileSync(manifest,'utf8'),before);
}finally{fs.rmSync(modelFolder,{recursive:true,force:true});delete global.Project;delete global.Group;}
console.log('BB script parsing, migration ownership, animation, input, worker algorithms and remote pose checks passed');

// Optional real Chromium probe: exercises the actual opaque iframe/Worker and WebGL shader compiler.
if(process.argv.includes('--browser')) {
    const folder=fs.mkdtempSync(path.join(os.tmpdir(),'rysm-browser-'));
    fs.copyFileSync(path.resolve(__dirname,'reimagined_ysm_physics_groups.js'),path.join(folder,'plugin.js'));
    const plugin='plugin.js';
    fs.writeFileSync(path.join(folder,'index.html'),`<!doctype html><meta charset="utf-8"><pre id="result">RUNNING</pre>
<script>window.onerror=(message,file,line)=>{document.querySelector('#result').textContent='ERROR '+message+' '+file+':'+line;};var Plugin=undefined;var module={exports:{}};var Project={};var Group={all:[]};var Cube={all:[]};</script>
<script src="${plugin}"></script><script>
const api=module.exports,results=[];
const report=text=>{document.querySelector('#result').textContent=JSON.stringify(results)+' '+text;};
const gl=document.createElement('canvas').getContext('webgl2');
if(gl){try{const shader=gl.createShader(gl.FRAGMENT_SHADER);gl.shaderSource(shader,'#version 300 es\\nprecision highp float;precision highp int;\\n'+api.cosmicPreviewFragment);gl.compileShader(shader);if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(shader));results.push('GLSL PASS');}catch(e){results.push('GLSL FAIL '+e.message);}}else results.push('WEBGL UNAVAILABLE');
api.startScriptPreview({'scripts/shared.js':'const shared=40;','scripts/main.js':'Rysm.molang("probe",ctx=>shared===40 && typeof fetch === "undefined" && typeof navigator === "undefined");'},()=>({entity:{local:true},camera:{yaw:0,pitch:0},molang:{},keys:[],bindings:[],remote:{}}),text=>{
report(text);if(text.includes('"probe": 1')){api.stopScriptPreview();results.push('WORKER PASS');
setTimeout(()=>api.startScriptPreview({'scripts/loop.js':'while(true){}'},()=>({}),text=>{report(text);if(text.includes('超时')){results.push('TIMEOUT PASS');report('PASS');}}),0);}
});
</script>`);
    console.log('BROWSER_PROBE='+path.join(folder,'index.html'));
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
            if(text.includes('TIMEOUT PASS')||text.includes('GLSL FAIL')||text.startsWith('ERROR'))break;
            await new Promise(resolve=>setTimeout(resolve,100));
        }
        console.log(text);assert(text.includes('GLSL PASS')&&text.includes('WORKER PASS')&&text.includes('TIMEOUT PASS'),'Real browser preview failed: '+text);
        await call('Browser.close');
    }finally{socket?.close();browser.kill();}
}
