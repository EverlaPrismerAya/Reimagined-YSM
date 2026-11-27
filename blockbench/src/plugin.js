/* global Plugin, BarMenu, MenuBar, THREE, Cube, Group, Animation, Project, Format, Undo, Modes, Blockbench, Canvas, Transformer, settings, Property, ModelProject, Dialog */
(() => {
    'use strict';
    /* RYSM_VENDOR */
    const fields = {
        a: ['最大偏转角 (°)', '90'], r: ['线性阻尼', '1'], air: ['空气阻力 (方块⁻¹)', '0.5'],
        s: ['回弹强度 (秒⁻²)', '20'], g: ['重力 (方块/秒²，负数向上)', '9.8'],
        gw: ['重力阱 (方块/秒²)', ''],
        c: ['碰撞半径 (模型像素)', '1'], bc: ['世界方块碰撞', 'false'],
        enable: ['启用条件', ''], disable: ['禁用条件（优先）', '']
    };
    const number = '[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?';
    const decimal = new RegExp(`^${number}$`);
    const comparison = new RegExp(`^(?:v|variable|rysmjs)\\.[a-z_][a-z_0-9]*(?:\\.[a-z_][a-z_0-9]*)*\\s*(?:>=|<=|!=|=|>|<)\\s*(${number})$`, 'i');

    const float = value => decimal.test(String(value)) && Number.isFinite(Math.fround(Number(value)));
    const effectKeys = ['physics','end','collider','facingCamera','glow','item','cosmic','outline','frameAnimation'];
    const clone = value => JSON.parse(JSON.stringify(value));
    const props = group => group?.rysm_properties || {};
    const isPhysics = group => !!props(group).physics;
    const physicsKind = group => props(group).physics?.kind === 'tail' ? 'tail' : 'normal';
    const supported = () => typeof Format !== 'undefined' && ['bedrock','bedrock_old'].includes(Format?.id);
    let menu, panel, frame, inspectorKey, draw, gizmos, scriptProperty, scriptOwnedProperty, scriptProjectProperty, scriptDraftProperty, scriptEditor;
    const minLength = 16e-6;
    const distance = (a,b) => Math.hypot(...a.map((n,i) => n-b[i]));
    const defaultEnd = origin => origin.map((n,i) => n-(i===1?4:0));
    const showPanel = () => panel.show();
    function uniqueName(base, except) {
        let name=base;
        for(let i=2;Group.all.some(group=>group!==except && group.name.toLowerCase()===name.toLowerCase());i++)name=base+'_'+i;
        return name;
    }
    function inheritValues(parent, local) {
        const values={...parent,...local};
        if(Object.hasOwn(local,'gw'))delete values.g;
        else if(Object.hasOwn(local,'g'))delete values.gw;
        return values;
    }
    function effectivePhysics(bone) {
        if(!isPhysics(bone))return {};
        const parent=isPhysics(bone.parent) && physicsKind(bone.parent)===physicsKind(bone) ? effectivePhysics(bone.parent) :
            physicsKind(bone)==='tail' ? {r:10,gw:3,s:16,a:50,air:0.5,c:1,bc:false} : {r:1,g:9.8,s:20,a:90,air:0.5,c:1,bc:false};
        return inheritValues(parent,props(bone).physics);
    }
    function validateProperties(properties) {
        if(!properties || typeof properties!=='object' || Array.isArray(properties))throw new Error('组属性必须为对象');
        for(const [key,value] of Object.entries(properties)) {
            if(!effectKeys.includes(key))throw new Error('未知组效果：'+key);
            if(typeof value!=='boolean' && (!value || typeof value!=='object' || Array.isArray(value)))throw new Error(key+' 必须为布尔值或对象');
            if(['end','collider','facingCamera','glow','item'].includes(key) && typeof value!=='boolean')throw new Error(key+' 必须为布尔值');
        }
        if(['physics','end','collider'].filter(key=>properties[key]).length>1)throw new Error('物理骨、末端和碰撞体角色互斥');
        const p=properties.physics;
        if(p && typeof p==='object') {
            if(Object.hasOwn(p,'g') && Object.hasOwn(p,'gw'))throw new Error('重力与重力阱不能同时设置');
            for(const [key,value] of Object.entries(p)) {
                if(key==='kind'){if(!['normal','tail'].includes(value))throw new Error('物理类型必须为 normal 或 tail');continue;}
                if(!Object.hasOwn(fields,key))throw new Error('未知物理参数：'+key);
                if(key==='enable'||key==='disable') {
                    const text=String(value).replace(/^\(|\)$/g,'');
                    const items=text.split(',').map(s=>s.trim());
                    if(!text || !(items.every(s=>/^[^\s(),=<>!@]+$/.test(s)) || items.every(s=>comparison.test(s))))throw new Error(key+' 条件无效');
                }else if(key==='bc') {
                    if(typeof value!=='boolean' && !['true','false'].includes(value))throw new Error('bc 必须为布尔值');
                }else if(!float(value) || (!['g','gw'].includes(key)&&+value<0) || (key==='a'&&+value>180))throw new Error(fields[key][0]+'：值无效');
            }
        }
        for(const key of ['cosmic','outline']) {
            const value=properties[key];if(!value || typeof value!=='object')continue;
            const allowed=key==='cosmic'?['sc','ac','sa','aa','effect']:['color','width'];
            for(const [field,raw] of Object.entries(value)) {
                if(!allowed.includes(field))throw new Error('未知 '+key+' 参数：'+field);
                if(field==='effect'){if(typeof raw!=='string'||!raw.trim()||/[\\/\x00-\x1f]/.test(raw)||['.','..'].includes(raw))throw new Error('星空效果名称无效');continue;}
                if(['sc','ac','color'].includes(field)) {
                    if(typeof raw!=='string'||!(/^[a-f\d]{6}$/i.test(raw)||field==='color'&&raw==='auto'))throw new Error(field+' 需要六位 RGB'+(field==='color'?' 或 auto':''));
                }else if(!float(raw)||+raw<0||+raw>(field==='width'?64:100))throw new Error(field+' 超出有效范围');
            }
        }
        const f=properties.frameAnimation;
        if(f && typeof f==='object') {
            for(const key of Object.keys(f))if(!['count','tick','macro','macroRepeat'].includes(key))throw new Error('未知帧动画参数：'+key);
            if(!Number.isInteger(+f.count)||+f.count<1||+f.count>2147483647)throw new Error('帧数必须为有效正整数');
            if(Object.hasOwn(f,'tick')===Object.hasOwn(f,'macro'))throw new Error('帧动画必须且只能指定 tick 或 macro');
            if(Object.hasOwn(f,'tick')&&(!Number.isFinite(+f.tick)||+f.tick<=0))throw new Error('帧间隔必须为有限正数');
            if(Object.hasOwn(f,'macro')&&(typeof f.macro!=='string'||!/^(?:v|variable|rysmjs)\.[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(f.macro)))throw new Error('帧宏必须为 v.*、variable.* 或 rysmjs.* 路径');
            if(Object.hasOwn(f,'macroRepeat')&&typeof f.macroRepeat!=='boolean')throw new Error('macroRepeat 必须为布尔值');
        }
        return properties;
    }
    function setProperties(group, properties, label='Edit RYSM group properties') {
        if(group.locked || !group.export)throw new Error('请选择未锁定、可导出的组');
        validateProperties(properties);
        if(properties.item || properties.frameAnimation) {
            const cubes=group.children.filter(child=>child instanceof Cube&&child.export);
            if(cubes.length!==1||Object.values(cubes[0].faces).filter(face=>face.texture!==null).length!==1)throw new Error('单面厚度和帧动画需要一个方块及一个有效 UV 面');
        }
        if(JSON.stringify(props(group))===JSON.stringify(properties)&&group.rysm_script_owned)return;
        Undo.initEdit({outliner:true,groups:[group]});
        group.rysm_properties=clone(properties);group.rysm_script_owned=true;
        Undo.finishEdit(label,{outliner:true,groups:[group]});
        inspectorKey=undefined;
    }
    function createPhysics(bone, kind='normal') {
        if(bone.locked||!bone.export)throw new Error('请先解锁骨骼并设为可导出');
        const branches=bone.children.filter(isPhysics), ends=bone.children.filter(child=>props(child).end);
        if(ends.length>1||ends.some(child=>child.locked||!child.export||child.children.length)||branches.length&&ends.length)throw new Error('请先修正骨链末端拓扑');
        const parent=isPhysics(bone.parent)?bone.parent:null;
        const parentEnds=parent?.children.filter(child=>props(child).end)||[];
        if(parent?.locked||parentEnds.length>1||parentEnds.some(child=>child.locked||!child.export||child.children.length)||
            parentEnds.length&&parent.children.some(child=>child!==bone&&isPhysics(child)))throw new Error('无法安全接入父骨链');
        if(parent&&distance(parent.origin,bone.origin)<=minLength||branches.some(child=>!child.export||distance(child.origin,bone.origin)<=minLength))throw new Error('相邻物理骨枢轴不能重合，且必须可导出');
        const move=parentEnds[0];
        if(move&&(branches.length||ends.length))throw new Error('请先移除父组末端，再接入已有骨链');
        const groups=[bone,...parentEnds];
        Undo.initEdit({outliner:true,groups:groups.slice()});
        const p=clone(props(bone));delete p.end;delete p.collider;p.physics=kind==='tail'?{kind:'tail'}:{};
        bone.rysm_properties=p;bone.rysm_script_owned=true;
        if(move) {
            if(distance(move.origin,bone.origin)<=minLength)move.origin.splice(0,3,...bone.origin.map((n,i)=>2*n-parent.origin[i]));
            move.addTo(bone);Canvas.updateAllBones([move]);
        }else if(!branches.length&&!ends.length) {
            const endpoint=new Group({name:uniqueName(bone.name+'_end'),origin:defaultEnd(bone.origin)}).addTo(bone).init();
            endpoint.rysm_properties={end:true};endpoint.rysm_script_owned=true;groups.push(endpoint);
        }
        bone.isOpen=true;
        Undo.finishEdit('Create RYSM physics chain',{outliner:true,groups});inspectorKey=undefined;
    }
    function removePhysics(bone) {
        const p=clone(props(bone));delete p.physics;
        const ends=bone.children.filter(child=>props(child).end);
        const parent=isPhysics(bone.parent)?bone.parent:null;
        const repair=parent&&!parent.children.some(child=>child!==bone&&(isPhysics(child)||props(child).end));
        if(ends.some(child=>child.locked)||repair&&parent.locked)throw new Error('请先解锁受影响的末端或父组');
        const removable=ends.filter(child=>!child.children.length&&!Animation.all.some(a=>a.animators[child.uuid]?.keyframes?.length));
        const animations=Animation.all.filter(a=>removable.some(child=>a.animators[child.uuid]));
        const groups=[bone,...ends];
        Undo.initEdit({outliner:true,groups:groups.slice(),animations});
        bone.rysm_properties=p;bone.rysm_script_owned=true;
        for(const child of ends) {
            if(removable.includes(child))child.remove(false);
            else {child.rysm_properties={...props(child)};delete child.rysm_properties.end;child.rysm_script_owned=true;}
        }
        if(repair) {
            const endpoint=new Group({name:uniqueName(parent.name+'_end'),origin:distance(parent.origin,bone.origin)>minLength?bone.origin.slice():defaultEnd(parent.origin)}).addTo(parent).init();
            endpoint.rysm_properties={end:true};endpoint.rysm_script_owned=true;groups.push(endpoint);
        }
        Undo.finishEdit('Remove RYSM physics',{outliner:true,groups:groups.filter(g=>!removable.includes(g)),animations});inspectorKey=undefined;
    }
    function physicsChain(group) {
        if(props(group).end)group=group.parent;
        if(!isPhysics(group))return [];
        while(isPhysics(group.parent)&&physicsKind(group.parent)===physicsKind(group))group=group.parent;
        const result=[];
        function visit(bone){result.push(bone);for(const child of bone.children.filter(isPhysics))if(physicsKind(child)===physicsKind(bone))visit(child);}
        visit(group);return result;
    }
    function inputRow(host,label,input) {
        const row=document.createElement('label'),caption=document.createElement('span');caption.textContent=label;
        row.style.cssText='display:flex;align-items:center;justify-content:space-between;gap:12px;margin:7px 0';
        input.style.maxWidth='55%';row.append(caption,input);host.append(row);return input;
    }
    function choice(host,label,options,value) {
        const input=document.createElement('select');
        for(const [key,text] of Object.entries(options)){const option=document.createElement('option');option.value=key;option.textContent=text;input.append(option);}
        input.value=value;return inputRow(host,label,input);
    }
    function valueInput(host,label,value='',placeholder='',type='number') {
        const input=document.createElement('input');input.type=type;input.value=value??'';input.placeholder=placeholder;
        if(type==='number')input.step='any';return inputRow(host,label,input);
    }
    function physicsForm(host,local={},effective={},batch=false) {
        if(typeof local!=='object')local={};
        const inputs={},kind=choice(host,'物理类型',{inherit:batch?'保持各骨类型':'默认物理骨',normal:'普通物理骨',tail:'尾骨'},local.kind||'inherit');
        const help=document.createElement('small');help.textContent=batch?'留空的参数不变；填写的参数应用于链上所有物理骨。':'留空表示继承；灰色占位数字为当前有效值。';host.append(help);
        for(const key of ['r','air','s','a','c']) {
            const input=inputs[key]=valueInput(host,fields[key][0],local[key],String(effective[key]??''));input.min=0;
            if(key==='a') {
                input.max=180;const slider=document.createElement('input');slider.type='range';slider.min=0;slider.max=180;slider.step=1;slider.value=local[key]??effective[key]??90;
                slider.setAttribute('aria-label','最大偏转角滑块');slider.oninput=()=>{input.value=slider.value;};input.oninput=()=>{if(input.value!=='')slider.value=input.value;};host.append(slider);
            }
        }
        const gravity=choice(host,'重力模式',{inherit:batch?'保持各骨重力':'继承父骨 / 类型默认',g:'向下重力（负数向上）',gw:'重力阱'},Object.hasOwn(local,'gw')?'gw':Object.hasOwn(local,'g')?'g':'inherit');
        const gravityValue=valueInput(host,'重力大小',local.gw??local.g,'有效值 '+(effective.gw??effective.g??9.8));
        gravity.onchange=()=>{gravityValue.disabled=gravity.value==='inherit';};gravity.onchange();
        const collision=choice(host,'世界方块碰撞',{inherit:batch?'保持各骨设置':'继承（当前 '+(effective.bc?'开启':'关闭')+'）',true:'开启',false:'关闭'},Object.hasOwn(local,'bc')?String(local.bc):'inherit');
        for(const key of ['enable','disable'])inputs[key]=valueInput(host,fields[key][0],local[key],batch?'留空不修改':'留空继承','text');
        return {read(){
            const p={};if(kind.value!=='inherit')p.kind=kind.value;
            for(const [key,input] of Object.entries(inputs))if(input.value.trim()!=='')p[key]=['enable','disable'].includes(key)?input.value.trim():Number(input.value);
            if(gravity.value!=='inherit'){if(gravityValue.value.trim()==='')throw new Error('请填写重力大小');p[gravity.value]=Number(gravityValue.value);}
            if(collision.value!=='inherit')p.bc=collision.value==='true';
            validateProperties({physics:p});return p;
        }};
    }
    function applyChainProperties(groups,values) {
        if(groups.some(g=>!Group.all.includes(g)||g.locked||!g.export||!isPhysics(g)))throw new Error('骨链已改变，或有锁定、不可导出的骨骼');
        values.forEach(p=>validateProperties({physics:p}));
        Undo.initEdit({outliner:true,groups});
        groups.forEach((g,i)=>{g.rysm_properties={...props(g),physics:clone(values[i])};g.rysm_script_owned=true;});
        Undo.finishEdit('Edit RYSM physics chain',{outliner:true,groups});inspectorKey=undefined;
    }
    function editPhysicsChain(group=Group.first_selected) {
        const groups=physicsChain(group),project=Project;
        if(!groups.length)throw new Error('请选择物理骨链或末端');
        const host=document.createElement('div'),status=document.createElement('p'),rows=[];
        const summary=document.createElement('p');summary.textContent=groups.map(g=>g.name).join(' → ')+' · '+groups.length+' 节物理骨';host.append(summary);
        const batch=document.createElement('details'),batchTitle=document.createElement('summary');batchTitle.textContent='统一设置整条链';batch.append(batchTitle);host.append(batch);
        const batchForm=physicsForm(batch,{},effectivePhysics(groups[0]),true),apply=document.createElement('button');apply.type='button';apply.textContent='将已填写参数应用到整条链';batch.append(apply);
        const section=document.createElement('div');host.append(section,status);status.setAttribute('role','status');
        let drafts=groups.map(g=>clone(typeof props(g).physics==='object'?props(g).physics:{}));
        function render(){section.replaceChildren();rows.length=0;groups.forEach((bone,i)=>{
            const details=document.createElement('details'),title=document.createElement('summary');title.textContent=(bone===group?'● ':'')+bone.name;details.open=bone===group||groups.length===1;details.append(title);section.append(details);
            rows.push(physicsForm(details,drafts[i],effectivePhysics(bone)));
        });}
        render();
        apply.onclick=()=>{try{const patch=batchForm.read();drafts=rows.map(row=>{const p=row.read();if(Object.hasOwn(patch,'g'))delete p.gw;if(Object.hasOwn(patch,'gw'))delete p.g;return {...p,...patch};});render();status.textContent='已填入整链参数，点击应用保存（支持撤销）。';}catch(error){status.textContent=error.message;}};
        new Dialog({id:'rysm_chain_editor',title:'整条骨链 · '+groups[0].name,width:650,lines:[host],buttons:['应用','取消'],
            onConfirm(){try{if(Project!==project)throw new Error('项目已切换');applyChainProperties(groups,rows.map(row=>row.read()));}catch(error){status.textContent=error.message;return false;}}
        }).show();
    }
    function effectForm(host,key,value) {
        if(key==='physics')return physicsForm(host,value,{});
        const p=value&&typeof value==='object'?value:{},inputs={};
        const specs=key==='cosmic'?{effect:['效果名称','text'],sc:['空间颜色 RGB','color'],sa:['空间混色 %','number'],ac:['星体颜色 RGB','color'],aa:['星体混色 %','number']}:key==='outline'?{color:['描边颜色（auto 自动）','color'],width:['描边宽度 0–64','number']}:{count:['帧数','number'],tick:['每帧 tick（与帧宏二选一）','number'],macro:['帧宏路径','text']};
        for(const [name,[label,type]] of Object.entries(specs)) {
            const input=inputs[name]=valueInput(host,label,p[name],name==='color'?'auto':'留空使用默认',type==='color'?'text':type);
            if(type==='color') {
                const picker=document.createElement('input');picker.type='color';picker.value=/^[0-9a-f]{6}$/i.test(input.value)?'#'+input.value:'#3388ff';picker.setAttribute('aria-label',label+'选色');
                picker.oninput=()=>{input.value=picker.value.slice(1).toUpperCase();};input.oninput=()=>{if(/^[0-9a-f]{6}$/i.test(input.value))picker.value='#'+input.value;};host.append(picker);
            }
        }
        if(key==='frameAnimation')inputs.macroRepeat=choice(host,'帧宏循环',{inherit:'默认',true:'循环',false:'钳制'},Object.hasOwn(p,'macroRepeat')?String(p.macroRepeat):'inherit');
        return {read(){const out={};for(const [name,input] of Object.entries(inputs))if(input.value!==''&&input.value!=='inherit')out[name]=name==='macroRepeat'?input.value==='true':['count','tick','width','sa','aa'].includes(name)?Number(input.value):input.value.trim();return out;}};
    }
    function editScriptProperties(group=Group.first_selected) {
        if(!group||group.locked||!group.export)throw new Error('请选择未锁定、可导出的组');
        const project=Project,p=clone(props(group)),host=document.createElement('div'),status=document.createElement('p'),forms={};
        const labels={physics:'物理骨',cosmic:'星空效果',outline:'描边',frameAnimation:'帧动画',end:'物理末端',collider:'身体碰撞体',facingCamera:'面向镜头',glow:'发光',item:'单面厚度'};
        for(const key of effectKeys) {
            const section=document.createElement('fieldset'),state=choice(section,labels[key],{inherit:'未声明 / 继承',enable:'启用',disable:'禁用'},Object.hasOwn(p,key)?p[key]===false?'disable':'enable':'inherit');
            const values=document.createElement('div');section.append(values);host.append(section);
            const form=['physics','cosmic','outline','frameAnimation'].includes(key)?effectForm(values,key,p[key]):null;
            state.onchange=()=>{values.hidden=state.value!=='enable';};state.onchange();forms[key]={state,form};
        }
        status.setAttribute('role','status');host.append(status);
        new Dialog({id:'rysm_js_group',title:'RYSM 组属性 · '+qualifiedName(group.name),width:650,lines:[host],
            onConfirm(){try{if(Project!==project||!Group.all.includes(group))throw new Error('项目或目标组已改变');const next={};for(const [key,{state,form}] of Object.entries(forms)){if(state.value==='disable')next[key]=false;else if(state.value==='enable')next[key]=form?form.read():true;}setProperties(group,next);}catch(error){status.textContent=error.message;return false;}}
        }).show();
    }
    function inspectGroup(bone) {
        const root=document.createElement('div'),heading=document.createElement('h3'),status=document.createElement('p');
        heading.textContent=bone.name;root.append(heading,associationNote(bone));status.setAttribute('role','status');
        const project=Project;
        const button=(label,action)=>{
            const node=document.createElement('button');node.type='button';node.textContent=label;
            node.onclick=()=>{try{if(Project===project&&Group.all.includes(bone)&&!bone.locked)action();}catch(e){status.textContent=e.message;}};
            root.append(node,document.createElement('br'));
        };
        button('编辑全部组属性',()=>editScriptProperties(bone));
        if(isPhysics(bone)) {
            const values=effectivePhysics(bone),help=document.createElement('p');
            help.textContent=(physicsKind(bone)==='tail'?'尾骨':'物理骨')+' · 偏转 '+values.a+'° · 碰撞半径 '+values.c+' · '+(Object.hasOwn(values,'gw')?'重力阱 '+values.gw:'重力 '+values.g);root.append(help);
            button('编辑整条骨链（'+physicsChain(bone).length+' 节）',()=>editPhysicsChain(bone));
            button('取消物理化',()=>removePhysics(bone));
            for(const child of bone.children.filter(child=>child instanceof Group&&child.export&&!child.locked&&!isPhysics(child)&&!props(child).end&&!props(child).collider))
                button('延长骨链：'+child.name,()=>{createPhysics(child,physicsKind(bone));child.select(new MouseEvent('click'),true);});
            if(!settings.export_empty_groups.value){const note=document.createElement('p');note.textContent='请启用 Export Empty Groups，确保末端导出。';root.append(note);}
        }else if(props(bone).end) {
            const note=document.createElement('p');note.textContent='物理末端：移动枢轴可调整骨链长度。';root.append(note);
            button('编辑整条骨链',()=>editPhysicsChain(bone));
        }else {
            button('创建物理骨及末端',()=>createPhysics(bone));
            button('创建尾骨及末端',()=>createPhysics(bone,'tail'));
            button('创建子碰撞体',createCollider);
        }
        root.append(status);panel.node.replaceChildren(root);
    }
    function selectionKey(bone) {
        return JSON.stringify(bone?[Project.uuid,bone.uuid,bone.name,props(bone),bone.origin,bone.locked,bone.export,
            bone.parent?.uuid,props(bone.parent),Cube.selected?.[0]?.uuid,bone.children.map(child=>[child.uuid,props(child),child.origin,child.export,child.locked,child.children?.length])]:[]);
    }
    function tick() {
        frame=requestAnimationFrame(tick);
        const active=supported()&&Modes.edit;
        if(supported()&&(Project!==associationProject||Date.now()>=nextAssociationCheck)) {
            associationProject=Project;nextAssociationCheck=Date.now()+1000;
            try{syncScriptGroups();}catch(error){
                const message='RYSM 配置读取失败：'+error.message;
                if(message!==associationError){associationError=message;Blockbench.showQuickMessage(message);}
            }
        }
        if(scriptEditor&&scriptEditor.project!==Project)scriptEditor.detach();
        updateOverlay(active);panel.sync(active);
        if(!active){clearGizmos();drawnKey=undefined;inspectorKey=undefined;return;}
        const cubeParent=Cube.selected?.[0]?.parent;
        const bone=props(cubeParent).collider?cubeParent:Group.first_selected;
        const key=selectionKey(bone);
        if(key!==inspectorKey) {
            inspectorKey=key;draw=undefined;
            if(bone&&!bone.locked&&bone.export) {
                if(props(bone).collider)inspectCollider(bone);else inspectGroup(bone);
            }else {panel.node.textContent='选择一个未锁定、可导出的组。';}
        }
        const outputs=[];
        if(isPhysics(bone)) {
            const anchor=bone.mesh?.getWorldPosition(new THREE.Vector3());
            if(anchor)for(const child of bone.children.filter(child=>isPhysics(child)||props(child).end)) {
                const target=child.mesh?.getWorldPosition(new THREE.Vector3()),p=effectivePhysics(bone);
                if(target)outputs.push({anchor,target,span:+p.a,radius:+p.c,note:bone.name+' · '+p.a+'°'});
            }
        }
        const geometryKey=JSON.stringify([showBones,showVolumes,outputs]);
        if(geometryKey!==drawnKey){drawnKey=geometryKey;renderGizmos(outputs);}
    }
    function clearGizmos() {
        if (!gizmos) return;
        gizmos.removeFromParent();
        gizmos.traverse(object => {
            object.geometry?.dispose();
            object.material?.map?.dispose();
            object.material?.dispose();
        });
        gizmos = undefined;
    }

    // Unit +Y is the target ray. Meridians show the full allowed spherical sector,
    // including angles past 90°, rather than an inverted finite cone.
    function sectorLines(degrees) {
        const max = degrees * Math.PI / 180;
        const lines = [];
        const point = (theta, azimuth) => [Math.sin(theta) * Math.cos(azimuth), Math.cos(theta), Math.sin(theta) * Math.sin(azimuth)];
        if (!max) return lines;
        for (let k = 0; k < 4; k++) {
            const azimuth = k * Math.PI / 2;
            for (let i = 0; i < 16; i++) lines.push(...point(max * i / 16, azimuth), ...point(max * (i + 1) / 16, azimuth));
            if (degrees < 180) lines.push(0, 0, 0, ...point(max, azimuth));
        }
        if (degrees < 180) for (let i = 0; i < 32; i++) lines.push(...point(max, i * Math.PI / 16), ...point(max, (i + 1) * Math.PI / 16));
        return lines;
    }

    function colliderCube(group) {
        const origin = group.origin.slice();
        const cube = new Cube({name: 'collision_box', origin, from: origin.map(n => n - 2), to: origin.map(n => n + 2), box_uv: false});
        cube.box_uv = false;
        for (const face of Object.values(cube.faces)) face.texture = null;
        return cube.addTo(group).init();
    }

    function addCollisionBox(group) {
        Undo.initEdit({outliner:true, elements:[], selection:true});
        const cube = colliderCube(group);
        group.isOpen = true;
        cube.select(new MouseEvent('click'), true);
        Undo.finishEdit('Add Reimagined YSM collision box', {outliner:true, elements:[cube], selection:true});
    }

    function createCollider() {
        const parent = Group.first_selected;
        for (let node = parent; node instanceof Group; node = node.parent) {
            if (node.locked || !node.export || isPhysics(node) || props(node).end || props(node).collider) {
                Blockbench.showQuickMessage('请选中可导出的普通骨骼，或取消选择后在模型根部创建。');
                return;
            }
        }
        const origin = parent?.origin.slice() ?? [0, 0, 0];
        Undo.initEdit({outliner:true, groups:[], elements:[], selection:true});
        const group = new Group({name:uniqueName(`${parent ? parent.name + '_' : ''}collider`), origin})
            .addTo(parent ?? 'root').init();
        group.rysm_properties = {collider:true};
        group.rysm_script_owned = true;
        const cube = colliderCube(group);
        group.isOpen = true;
        cube.select(new MouseEvent('click'), true);
        Undo.finishEdit('Create Reimagined YSM body collider', {outliner:true, groups:[group], elements:[cube], selection:true});
        showPanel();
    }

    function inspectCollider(group) {
        const project = Project;
        const root = document.createElement('div');
        root.style.cssText = 'padding:8px;max-height:55vh;overflow:auto';
        const title = document.createElement('p');
        title.textContent = `${group.name} · 无 UV 方块也可从这里选中，再用原生移动、缩放、旋转工具编辑。`;
        root.append(title,associationNote(group));
        const button = (label, click, disabled = false) => {
            const element = document.createElement('button');
            element.type = 'button';
            element.textContent = label;
            element.disabled = disabled;
            element.addEventListener('click', () => {
                if (Project === project && Group.all.includes(group) && !group.locked) click();
            });
            root.append(element, document.createElement('br'));
        };
        button('编辑全部组属性', () => editScriptProperties(group));
        button('添加无 UV 碰撞块', () => addCollisionBox(group));
        group.children.filter(child => child instanceof Cube).forEach((cube, i) => {
            button(`${cube.selected ? '● ' : ''}${i + 1}. ${cube.name}`, () => {
                if (cube.parent === group) cube.select(new MouseEvent('click'), true);
            }, cube.locked);
        });
        panel.node.replaceChildren(root);
    }

    const preferencesKey = 'reimagined_ysm_physics_groups.preferences';
    function loadPreferences() {
        try {
            const value = JSON.parse(localStorage.getItem(preferencesKey));
            return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
        }
        catch { return {}; }
    }
    const preferences = loadPreferences();
    function savePreferences(values) {
        Object.assign(preferences, values);
        try { localStorage.setItem(preferencesKey, JSON.stringify(preferences)); }
        catch { /* The editor remains usable when local storage is unavailable. */ }
    }
    const savedSwitch = (key, fallback) => typeof preferences[key] === 'boolean' ? preferences[key] : fallback;

    let showBones = savedSwitch('showBones', true), showVolumes = savedSwitch('showVolumes', true);
    let pickColliders = savedSwitch('pickColliders', false), pickPhysicalGroups = savedSwitch('pickPhysicalGroups', false), overlay;
    const usable = group => group instanceof Group && group.export;
    function physicsTopology(groups) {
        const parameters = new Map();
        function resolve(bone) {
            if (!usable(bone) || !isPhysics(bone)) return null;
            if (!parameters.has(bone)) {
                try { parameters.set(bone, effectivePhysics(bone)); }
                catch { parameters.set(bone, null); }
            }
            return parameters.get(bone);
        }
        const segments = [];
        const distinct = (a, b) => Math.hypot(...a.origin.map((x, i) => x - b.origin[i])) > 16e-6;
        function chain(bone) {
            const children = bone.children.filter(child => resolve(child));
            const ends = bone.children.filter(child => usable(child) && props(child).end);
            const valid = children.filter(child => distinct(child, bone)).map(child => [child, chain(child)]).filter(([, result]) => result);
            if (valid.length !== children.length || (valid.length ? ends.length : (ends.length !== 1 || ends[0].children.length || !distinct(ends[0], bone)))) return null;
            const radius = Number(resolve(bone).c ?? fields.c[1]);
            return valid.length ? valid.flatMap(([child, result]) => [{bone, target:child, radius}, ...result])
                : [{bone, target:ends[0], radius}];
        }
        for (const bone of groups) if (resolve(bone) && !resolve(bone.parent)) segments.push(...(chain(bone) ?? []));
        const cubes = [];
        for (const group of groups) if (usable(group) && props(group).collider) {
            let ignored = false;
            for (let ancestor = group.parent; ancestor instanceof Group; ancestor = ancestor.parent) {
                if (resolve(ancestor) || props(ancestor).end) { ignored = true; break; }
            }
            if (!ignored) cubes.push(...group.children.filter(child => child instanceof Cube && child.export));
        }
        return {segments, cubes};
    }

    function capsuleLines(length, radius) {
        const lines = [];
        const point = (y, i, r = radius) => [r * Math.cos(i * Math.PI / 8), y, r * Math.sin(i * Math.PI / 8)];
        for (const y of [0, length]) for (let i = 0; i < 16; i++) lines.push(...point(y, i), ...point(y, i + 1));
        for (let i = 0; i < 16; i += 4) {
            lines.push(...point(0, i), ...point(length, i));
            for (const [y, direction] of [[0, -1], [length, 1]]) {
                const cap = step => {
                    const a = step * Math.PI / 16;
                    return point(y + direction * radius * Math.sin(a), i, radius * Math.cos(a));
                };
                for (let step = 0; step < 8; step++) lines.push(...cap(step), ...cap(step + 1));
            }
        }
        return lines;
    }

    function overlayLines(positions, color) {
        const geometry = new THREE.BufferGeometry();
        geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
        const object = new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({color, depthTest:false, depthWrite:false}));
        object.renderOrder = 98;
        object.raycast = () => {};
        overlay.add(object);
        return object;
    }

    function disposeOverlay() {
        overlay?.removeFromParent();
        overlay?.traverse(object => { object.geometry?.dispose(); object.material?.dispose(); });
        overlay = undefined;
        overlays = undefined;
    }

    function colliderParentKey(group) {
        const ids = [];
        for (let parent = group.parent; parent instanceof Group; parent = parent.parent) ids.push(parent.uuid);
        return ids;
    }

    let overlays, topologyKey;
    function updateOverlay(active) {
        if (!active || (!showBones && !showVolumes)) { disposeOverlay(); return; }
        // Rebuild only on topology/shape changes. Animation only updates transforms.
        const groups = Group.all.filter(group => isPhysics(group) || props(group).end || props(group).collider);
        const key = JSON.stringify([Project.uuid, showBones, showVolumes, groups.map(group => [group.uuid, group.name, props(group),
            group.export, group.origin, props(group).collider ? colliderParentKey(group) : group.parent?.uuid, props(group).collider || props(group).end
                ? group.children.map(child => [child.uuid, child.export, child.from, child.to, child.inflate, child.origin]) : null])]);
        if (!overlays || key !== topologyKey) {
            disposeOverlay();
            topologyKey = key;
            const {segments, cubes} = physicsTopology(groups);
            overlay = new THREE.Group();
            overlay.name = 'Reimagined YSM live physics overlay';
            Canvas.scene.add(overlay);
            overlays = {
                segments: segments.map(segment => ({...segment, length:undefined,
                    line:showBones ? overlayLines([0,0,0,0,1,0], 0xffd166) : null,
                    volume:showVolumes && segment.radius > 0 ? overlayLines([], 0x53e3aa) : null})),
                cubes: showVolumes ? cubes.map(cube => {
                    // Reimagined YSM bakes body colliders even when every UV face is absent.
                    // Use exported dimensions, not the render mesh's UV-dependent index buffer.
                    const inflate = cube.inflate || 0;
                    const min = cube.from.map((x, i) => x - cube.origin[i] - inflate);
                    const max = cube.to.map((x, i) => x - cube.origin[i] + inflate);
                    const corners = Array.from({length:8}, (_, mask) => min.map((x, i) => mask & (1 << i) ? max[i] : x));
                    const positions = [];
                    for (let mask = 0; mask < 8; mask++) for (const bit of [1, 2, 4]) {
                        if (!(mask & bit)) positions.push(...corners[mask], ...corners[mask | bit]);
                    }
                    const object = overlayLines(positions, 0xff9161);
                    object.matrixAutoUpdate = false;
                    return {cube, object, bounds:new THREE.Box3(new THREE.Vector3(...min), new THREE.Vector3(...max))};
                }) : []
            };
        }
        if (!overlays.segments.length && !overlays.cubes.length) return;
        overlay.updateWorldMatrix(true, false);
        const toLocal = overlay.matrixWorld.clone().invert();
        for (const item of overlays.segments) {
            const {bone, target, line, volume, radius} = item;
            const a = bone.mesh?.getWorldPosition(new THREE.Vector3())?.applyMatrix4(toLocal);
            const b = target.mesh?.getWorldPosition(new THREE.Vector3())?.applyMatrix4(toLocal);
            const direction = a && b?.sub(a);
            const length = direction?.length();
            const visible = length > 1e-6 && bone.visibility && target.visibility;
            for (const object of [line, volume]) if (object) {
                object.visible = !!visible;
                if (!visible) continue;
                object.position.copy(a);
                object.quaternion.setFromUnitVectors(new THREE.Vector3(0,1,0), direction.clone().divideScalar(length));
            }
            if (line && visible) line.scale.y = length;
            if (volume && visible && (item.length === undefined || Math.abs(item.length - length) > 1e-5)) {
                item.length = length;
                volume.geometry.setAttribute('position', new THREE.Float32BufferAttribute(capsuleLines(length, radius), 3));
                volume.geometry.computeBoundingSphere();
            }
        }
        for (const {cube, object} of overlays.cubes) {
            const mesh = cube.mesh;
            object.visible = !!(mesh && cube.visibility && cube.parent.visibility);
            object.material.color.setHex(cube.selected ? 0xffe29a : 0xff9161);
            if (!object.visible) continue;
            mesh.updateWorldMatrix(true, false);
            object.matrix.multiplyMatrices(toLocal, mesh.matrixWorld);
            object.matrixWorldNeedsUpdate = true;
        }
    }

    function renderGizmos(outputs) {
        clearGizmos();
        if (!outputs.length || (!showBones && !showVolumes)) return;
        gizmos = new THREE.Group();
        gizmos.name = 'Reimagined YSM physics gizmos';
        Canvas.scene.add(gizmos);
        const lines = (parent, positions, color) => {
            const geo = new THREE.BufferGeometry();
            geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
            const object = new THREE.LineSegments(geo, new THREE.LineBasicMaterial({color, depthTest:false, depthWrite:false}));
            object.renderOrder = 100;
            object.raycast = () => {};
            parent.add(object);
        };
        outputs.forEach(({anchor, target, span, radius, note}, index) => {
            const delta = target.clone().sub(anchor), length = delta.length();
            if (!Number.isFinite(length) || length <= 1e-6) return;
            const axis = new THREE.Group();
            axis.position.copy(anchor);
            axis.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), delta.divideScalar(length));
            gizmos.add(axis);
            if (showBones) lines(axis, [0, 0, 0, 0, length, 0], 0xffd166);
            if (showBones) lines(axis, sectorLines(span).map(n => n * length), 0x58aaff);
            if (showVolumes && Number.isFinite(radius) && radius > 0) {
                const ring = [];
                const point = i => [radius * Math.cos(i * Math.PI / 12), length / 2, radius * Math.sin(i * Math.PI / 12)];
                for (let i = 0; i < 24; i++) ring.push(...point(i), ...point(i + 1));
                lines(axis, ring, 0x53e3aa);
            }
            if (index || !showBones) return;
            const canvas = document.createElement('canvas');
            canvas.width = 512;
            canvas.height = 64;
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = '#152638ee';
            ctx.fillRect(0, 0, 512, 64);
            ctx.font = '32px sans-serif';
            ctx.fillStyle = '#ffffff';
            ctx.textAlign = 'center';
            ctx.fillText(note, 256, 43, 492);
            const sprite = new THREE.Sprite(new THREE.SpriteMaterial({map: new THREE.CanvasTexture(canvas), transparent:true, depthTest:false, depthWrite:false}));
            sprite.scale.set(10, 1.25, 1);
            sprite.position.copy(target).add(new THREE.Vector3(0, 1, 0));
            sprite.renderOrder = 101;
            sprite.raycast = () => {};
            gizmos.add(sprite);
        });
    }

    let drawnKey;
    function hitCollider(event, preview) {
        if (!pickColliders || !showVolumes || !overlays?.cubes.length) return;
        const rect = preview.canvas.getBoundingClientRect();
        const ray = new THREE.Raycaster();
        ray.setFromCamera(new THREE.Vector2(2 * (event.clientX - rect.left) / rect.width - 1,
            1 - 2 * (event.clientY - rect.top) / rect.height), preview.camera);
        let nearest = ray.far, picked;
        for (const {cube, object, bounds} of overlays.cubes) {
            const mesh = cube.mesh;
            if (!object.visible || !mesh || cube.isEffectivelyLocked?.() || cube.locked || cube.parent.locked) continue;
            mesh.updateWorldMatrix(true, false);
            if (!mesh.matrixWorld.determinant()) continue;
            const hit = ray.ray.clone().applyMatrix4(mesh.matrixWorld.clone().invert()).intersectBox(bounds, new THREE.Vector3());
            if (!hit) continue;
            const distance = hit.applyMatrix4(mesh.matrixWorld).distanceTo(ray.ray.origin);
            if (distance >= ray.near && distance < nearest) { nearest = distance; picked = cube; }
        }
        return picked;
    }

    let pickedGesture = false;
    const clearPickedGesture = () => { pickedGesture = false; };
    function pickPhysics(event) {
        if (event.button !== 0) return;
        clearPickedGesture();
        const preview = event.target.preview;
        if ((!pickColliders && !pickPhysicalGroups) || !supported() || !Modes.edit || !preview?.camera ||
            event.altKey || event.shiftKey || event.ctrlKey || event.metaKey ||
            Transformer.dragging || Transformer.hoverAxis != null) return;
        let target = hitCollider(event, preview);
        if (!target && pickPhysicalGroups) {
            const hit = preview.raycast(event);
            if (hit?.type === 'element' && hit.element instanceof Cube) {
                for (let group = hit.element.parent; group instanceof Group; group = group.parent) {
                    if (props(group).end || props(group).collider) break;
                    if (isPhysics(group)) { target = group; break; }
                }
            }
        }
        if (!target || target.locked || target.isEffectivelyLocked?.() || Blockbench.hasFlag('renaming')) return;
        // Own both halves of this gesture. A native mouseup without its native
        // mousedown can be interpreted as an empty click (even in another pane).
        pickedGesture = true;
        event.preventDefault();
        event.stopImmediatePropagation();
        target.select(new MouseEvent('click'), true);
        if (settings.outliner_reveal_on_select?.value) target.showInOutliner?.();
    }

    function finishPhysicsPick(event) {
        if (event.button !== 0 || !pickedGesture) return;
        clearPickedGesture();
        if (!event.target.preview?.camera) return;
        event.preventDefault();
        event.stopImmediatePropagation();
    }

    function makeFloatingInspector() {
        const root = document.createElement('section');
        root.id = 'rysm_physics_float';
        root.hidden = true;
        root.setAttribute('aria-label', 'Reimagined YSM 物理组检查器');
        root.style.cssText = 'position:fixed;top:64px;right:24px;z-index:25;width:min(360px,calc(100vw - 20px));max-height:calc(100vh - 16px);display:flex;flex-direction:column;background:var(--color-back);color:var(--color-text);border:1px solid var(--color-border);border-radius:8px;box-shadow:0 8px 24px #0008;overflow:hidden';
        const title = document.createElement('div');
        title.className = 'rysm-titlebar';
        title.style.cssText = 'display:flex;align-items:center;justify-content:space-between;padding:6px 10px;cursor:move;touch-action:none;user-select:none;background:var(--color-ui)';
        const heading = document.createElement('strong');
        heading.textContent = 'Reimagined YSM';
        heading.className = 'rysm-title';
        const close = document.createElement('button');
        close.type = 'button'; close.className = 'rysm-close';
        close.append(Blockbench.getIconNode('close'));
        close.title = '收起'; close.setAttribute('aria-label', '收起物理组检查器');
        title.append(Blockbench.getIconNode('icon-vertexsnap'), heading, close);
        const node = document.createElement('div');
        node.style.cssText = 'overflow:auto;min-height:0;padding:4px';
        node.className = 'rysm-inspector';
        const tools = document.createElement('div');
        tools.className = 'rysm-tools';
        const create = document.createElement('button');
        create.type = 'button';
        create.className = 'rysm-create';
        create.append(Blockbench.getIconNode('add_box'), '创建身体碰撞组');
        create.onclick = createCollider;
        const toggles = document.createElement('div');
        const toggle = (key, label, checked, change) => {
            const row = document.createElement('label');
            const input = document.createElement('input');
            input.type = 'checkbox';
            input.setAttribute('role', 'switch');
            input.checked = checked;
            input.onchange = () => { change(input.checked); savePreferences({[key]:input.checked}); };
            const track = document.createElement('span');
            track.className = 'rysm-switch-track';
            track.setAttribute('aria-hidden', 'true');
            row.append(label, input, track);
            toggles.append(row);
            return input;
        };
        toggle('showBones', '显示物理骨链', showBones, value => { showBones = value; });
        const volumes = toggle('showVolumes', '显示碰撞体积', showVolumes, value => { showVolumes = value; });
        const picker = toggle('pickColliders', '点击碰撞块选中', pickColliders, value => {
            pickColliders = value;
            if (value) {
                showVolumes = volumes.checked = true;
                savePreferences({showVolumes:true});
            }
        });
        picker.parentElement.title = '开启时自动显示碰撞体积；可选中模型内部无 UV 的身体碰撞块。隐藏碰撞体积时暂停点选。';
        toggle('pickPhysicalGroups', '点击方块选中物理组', pickPhysicalGroups, value => { pickPhysicalGroups = value; });
        const caption = document.createElement('div');
        caption.className = 'rysm-section';
        caption.textContent = '显示与点选';
        const selectionTitle = caption.cloneNode();
        selectionTitle.textContent = '当前选择';
        tools.append(caption, toggles);
        const tabs = document.createElement('div'); tabs.className = 'rysm-tabs'; tabs.setAttribute('role', 'tablist');
        const editor = document.createElement('div'); editor.className = 'rysm-editor';
        editor.style.cssText = 'overflow:auto;min-height:0'; const scripts=document.createElement('div'),newScript=document.createElement('button'),openScript=document.createElement('button');
        newScript.type=openScript.type='button';newScript.textContent='创建新脚本';openScript.textContent='脚本编辑器';
        newScript.onclick=()=>editScripts(true);openScript.onclick=()=>editScripts();scripts.append(newScript,openScript);
        editor.append(scripts,create, selectionTitle, node);
        tools.style.cssText = 'overflow:auto;min-height:0';
        let activeTab = preferences.tab === 'viewport' ? 'viewport' : 'groups';
        const tabButtons = [];
        function selectTab(value) {
            activeTab = value;
            tools.hidden = value !== 'viewport'; editor.hidden = value !== 'groups';
            for (const button of tabButtons) { button.setAttribute('aria-selected', String(button.dataset.tab === value)); }
            savePreferences({tab:value});
        }
        for (const [value, label] of [['groups', '分组编辑'], ['viewport', '视口辅助']]) {
            const button = document.createElement('button'); button.type = 'button'; button.textContent = label;
            button.dataset.tab = value; button.setAttribute('role', 'tab');
            button.onclick = () => selectTab(value); tabButtons.push(button); tabs.append(button);
        }
        selectTab(activeTab);
        root.append(title, tabs, editor, tools);
        const ball = document.createElement('button');
        ball.type = 'button'; ball.id = 'rysm_physics_ball'; ball.append(Blockbench.getIconNode('icon-vertexsnap')); ball.title = '展开 Reimagined YSM 物理组检查器';
        ball.setAttribute('aria-controls', root.id);
        ball.style.cssText = 'position:fixed;right:24px;top:64px;z-index:25;width:44px;height:44px;border-radius:50%;background:var(--color-accent);color:white;touch-action:none;user-select:none';
        const style = document.createElement('style');
        style.textContent = `
            #rysm_physics_float { --rysm-grad:linear-gradient(125deg,#51e5df,#ff9ed5); background:rgba(26,29,41,.96)!important; color:#eef1f9!important; border:1px solid #ffffff24!important; border-radius:14px!important; box-shadow:0 12px 36px #000a!important; backdrop-filter:blur(12px); }
            #rysm_physics_float .rysm-titlebar { flex:0 0 auto; min-height:44px; box-sizing:border-box; overflow:visible; gap:9px; padding:10px 13px!important; background:linear-gradient(110deg,#4bdedc1d,#ff9ed522)!important; border-bottom:1px solid #ffffff15; }
            #rysm_physics_float .rysm-titlebar > .icon { color:#74eee4; }
            #rysm_physics_float .rysm-title { flex:1; min-width:0; line-height:1.35; white-space:nowrap; font-size:15px; letter-spacing:.025em; background:var(--rysm-grad); background-clip:text; color:transparent; }
            #rysm_physics_float button { border:1px solid #ffffff20; border-radius:7px; background:#ffffff0d; color:inherit; min-width:0; min-height:26px; padding:3px 9px; cursor:pointer; }
            #rysm_physics_float button:hover:not(:disabled) { background:#ffffff20; }
            #rysm_physics_float button.rysm-close { flex:0 0 24px; display:grid; place-items:center; min-width:0; min-height:0; width:24px; height:24px; border:0; padding:0; background:transparent; opacity:.65; }
            #rysm_physics_float button.rysm-close:hover { opacity:1; background:#ffffff12; }
            #rysm_physics_float .rysm-close .icon { font-size:19px; line-height:1; }
            #rysm_physics_float button:disabled { opacity:.45; cursor:default; }
            #rysm_physics_float .rysm-section { padding:8px 12px 4px; color:#aeb9d1; font-size:11px; letter-spacing:.12em; }
            #rysm_physics_float .rysm-tools { border-bottom:1px solid #ffffff15; padding-bottom:6px; }
            #rysm_physics_float .rysm-create { display:flex; align-items:center; gap:6px; margin:3px 12px 6px; }
            #rysm_physics_float .rysm-tools label { display:flex; justify-content:space-between; align-items:center; padding:4px 13px; cursor:pointer; }
            #rysm_physics_float .rysm-tools label:hover { background:#ffffff09; }
            #rysm_physics_float .rysm-tools label { position:relative; }
            #rysm_physics_float .rysm-tools input[role=switch] { position:absolute; width:1px; height:1px; min-width:0; min-height:0; padding:0; margin:0; opacity:0; pointer-events:none; }
            #rysm_physics_float .rysm-switch-track { flex:0 0 34px; height:18px; border-radius:12px; background:#ffffff26; position:relative; }
            #rysm_physics_float .rysm-switch-track::after { content:''; position:absolute; left:2px; top:2px; width:14px; height:14px; border-radius:50%; background:white; transition:transform .15s; }
            #rysm_physics_float [role=switch]:checked + .rysm-switch-track { background:var(--rysm-grad); }
            #rysm_physics_float [role=switch]:checked + .rysm-switch-track::after { transform:translateX(16px); }
            #rysm_physics_float [role=switch]:disabled + .rysm-switch-track { opacity:.4; }
            #rysm_physics_float [role=switch]:focus-visible + .rysm-switch-track { outline:2px solid #79e6df; outline-offset:2px; }
            #rysm_physics_float [hidden] { display:none!important; }
            #rysm_physics_float .rysm-tabs, #rysm_physics_float .rysm-action-grid { display:grid;grid-template-columns:1fr 1fr;gap:6px;padding:8px; }
            #rysm_physics_float .rysm-tabs button[aria-selected=true] { background:#51e5df30;border-color:#51e5df; }
            #rysm_physics_float h3 { overflow-wrap:anywhere; margin:8px 0; }
            #rysm_physics_float .rysm-inspector { scrollbar-width:thin; padding:4px 8px 8px!important; }
            #rysm_physics_float .rysm-inspector p { line-height:1.4; margin:5px 0; }
            #rysm_physics_float .rysm-inspector input:not([type=range]), #rysm_physics_float .rysm-inspector select { border:1px solid #ffffff20; border-radius:5px; background:#10131e; color:#eef1f9; }
            #rysm_physics_float input[type=range] { accent-color:#79e6df; }
            #rysm_physics_float :focus-visible, #rysm_physics_ball:focus-visible { outline:2px solid #79e6df; outline-offset:2px; }
            #rysm_physics_ball { display:grid; place-items:center; box-sizing:border-box; min-width:44px; max-width:44px; min-height:44px; max-height:44px; padding:0; border:1px solid #ffffff75; background:linear-gradient(135deg,#51e5df 8%,#ff9ed5 92%)!important; color:#20243a!important; box-shadow:0 4px 16px #0008; cursor:grab; transition:filter .15s, scale .15s; }
            #rysm_physics_ball .icon { color:inherit; font-size:24px; }
            #rysm_physics_ball:hover { filter:brightness(1.14); scale:1.06; }
        `;
        document.head.append(style);
        document.body.append(root, ball);

        function place(element, x, y) {
            const rect = element.getBoundingClientRect();
            element.style.right = 'auto';
            element.style.left = `${Math.max(0, Math.min(x, innerWidth - rect.width))}px`;
            element.style.top = `${Math.max(0, Math.min(y, innerHeight - rect.height))}px`;
        }
        function draggable(element, handle, key, tap) {
            let start;
            handle.addEventListener('pointerdown', event => {
                if (!event.isPrimary || event.button !== 0 || close.contains(event.target)) return;
                const {left, top} = element.getBoundingClientRect();
                start = {id:event.pointerId, x:event.clientX, y:event.clientY, left, top, moved:false};
                handle.setPointerCapture(event.pointerId);
            });
            handle.addEventListener('pointermove', event => {
                if (start?.id !== event.pointerId) return;
                const dx = event.clientX - start.x, dy = event.clientY - start.y;
                if (start.moved ||= Math.hypot(dx, dy) >= 4) place(element, start.left + dx, start.top + dy);
            });
            handle.addEventListener('pointerup', event => {
                if (start?.id !== event.pointerId) return;
                if (start.moved) {
                    savePreferences({[key]:[parseFloat(element.style.left), parseFloat(element.style.top)]});
                } else tap?.();
                start = undefined;
            });
            handle.addEventListener('lostpointercapture', () => { start = undefined; });
        }
        let open = savedSwitch('open', true);
        function sync(available) {
            const expanded = available && open;
            const collapsed = available && !open;
            const update = (element, visible, display, key) => {
                if (element.style.display === (visible ? display : 'none')) return;
                element.style.display = visible ? display : 'none';
                if (visible) {
                    const rect = element.getBoundingClientRect();
                    const saved = preferences[key];
                    place(element, Number.isFinite(saved?.[0]) ? saved[0] : rect.left,
                        Number.isFinite(saved?.[1]) ? saved[1] : rect.top);
                }
            };
            update(root, expanded, 'flex', 'panelPosition');
            update(ball, collapsed, 'grid', 'ballPosition');
            create.disabled = !Modes.edit;
            node.hidden = !Modes.edit;
            selectionTitle.hidden = !Modes.edit;
            return expanded;
        }
        const available = () => supported() && Modes.edit;
        const setOpen = value => {
            if (open !== value) savePreferences({open:value});
            open = value;
            sync(available());
        };
        const show = () => setOpen(true);
        close.onclick = () => { setOpen(false); if (available()) ball.focus(); };
        // Mouse/touch use pointer capture; the zero-detail click is for keyboard activation.
        ball.onclick = event => { if (!event.detail) show(); };
        draggable(root, title, 'panelPosition');
        draggable(ball, ball, 'ballPosition', show);
        const resize = () => {
            for (const element of [root, ball]) if (element.style.display !== 'none') {
                const rect = element.getBoundingClientRect();
                place(element, rect.left, rect.top);
            }
        };
        window.addEventListener('resize', resize);
        const observer = new ResizeObserver(resize);
        observer.observe(root);
        root.hidden = false;
        sync(available());
        return {node, show, sync, delete() {
            observer.disconnect();
            window.removeEventListener('resize', resize);
            root.remove(); ball.remove(); style.remove();
        }};
    }


    const generatedFile='scripts/generated.blockbench.js';
    const generatedHeader='// Generated by Blockbench / 由 Blockbench 自动生成。\n// This file is overwritten on save. Write advanced logic in a separate script.\n// 此文件会在保存时自动生成并覆盖，请勿在此编写高级内容；请使用独立脚本文件。\n';
    const generatedPath=name=>name.split('/').pop().startsWith('generated.');
    const isGeneratedSource=source=>typeof source==='string'&&/^\/\/ (?:Generated by (?:Blockbench|the RYSM)|Automatically migrated|Migrated binary model|Migration registrations)/.test(source);
    function validScriptFile(name) {
        return typeof name==='string'&&name.startsWith('scripts/')&&name.endsWith('.js')&&!/[\\:\x00-\x1f]/.test(name)&&name.split('/').every(part=>part&&part!=='.'&&part!=='..');
    }
    function scriptPath(name) {
        if(!validScriptFile(name)||generatedPath(name)||['scripts/blockbench.js','scripts/migrated.js','scripts/prefix-migrated.js'].includes(name))throw new Error('手写脚本须为 scripts/名称.js；不能使用 generated. 前缀或覆盖生成/迁移文件');
        return name;
    }
    function readOnlyScript(name,source) {return generatedPath(name)||['scripts/blockbench.js','scripts/migrated.js','scripts/prefix-migrated.js'].includes(name)||isGeneratedSource(source);}
    function literal(node) {
        if(node?.type==='Literal'&&!node.regex&&['string','number','boolean'].includes(typeof node.value))return node.value;
        if(node?.type==='UnaryExpression'&&['-','+'].includes(node.operator)&&node.argument.type==='Literal'&&typeof node.argument.value==='number')return node.operator==='-'?-node.argument.value:node.argument.value;
        if(node?.type==='ArrayExpression')return node.elements.map(literal);
        if(node?.type==='ObjectExpression') {
            const out={};
            for(const p of node.properties) {
                if(p.type!=='Property'||p.computed||p.method||p.shorthand||p.kind!=='init')throw new Error('生成文件包含非声明式内容');
                const key=p.key.name??p.key.value;
                if(typeof key!=='string'||['__proto__','constructor','prototype'].includes(key)||Object.hasOwn(out,key))throw new Error('无效或重复属性');
                out[key]=literal(p.value);
            }
            return out;
        }
        throw new Error('生成文件包含手写逻辑，无法安全迁移；请先手动拆分');
    }
    const memberName=node=>node?.type==='MemberExpression'&&!node.computed?node.property.name:null;
    function mergeProperties(target, patch) {
        for(const [key,value] of Object.entries(patch))target[key]=value&&typeof value==='object'&&!Array.isArray(value)&&target[key]&&typeof target[key]==='object'?{...target[key],...value}:clone(value);
        return target;
    }
    function generatedGroups(source) {
        if(!isGeneratedSource(source))throw new Error('文件不是可识别的生成文件，已保留原内容');
        const ast=scriptParser.parse(source,{ecmaVersion:6}),entries=[];
        const fail=()=>{throw new Error('生成文件包含手写逻辑，已保留原内容；请先将高级内容移至独立文件');};
        function chain(node,event) {
            if(node?.type!=='CallExpression')return fail();
            const key=memberName(node.callee),receiver=node.callee.object;
            if(key==='modify'&&receiver?.type==='Identifier'&&receiver.name===event) {
                if(node.arguments.length<1||node.arguments.length>2)fail();
                const name=literal(node.arguments[0]);if(typeof name!=='string'||!name)fail();
                return [name,node.arguments.length===2?validateProperties(literal(node.arguments[1])):{}];
            }
            if(!effectKeys.includes(key)||node.arguments.length>1)fail();
            const result=chain(receiver,event);mergeProperties(result[1],{[key]:node.arguments.length?literal(node.arguments[0]):true});return result;
        }
        for(const statement of ast.body) {
            const call=statement.type==='ExpressionStatement'?statement.expression:null;
            if(call?.type!=='CallExpression'||call.callee.object?.name!=='Rysm')fail();
            // Recognize old generated JS only for safe file upgrades; no group-name syntax is interpreted.
            if(memberName(call.callee)==='group') {
                if(call.arguments.length!==2)fail();
                const name=literal(call.arguments[0]);if(typeof name!=='string'||!name)fail();
                entries.push([name,validateProperties(literal(call.arguments[1]))]);continue;
            }
            if(memberName(call.callee)!=='model'||call.arguments.length!==2||literal(call.arguments[0])!=='group_modifier')fail();
            const callback=call.arguments[1];
            if(!['ArrowFunctionExpression','FunctionExpression'].includes(callback?.type)||callback.params.length!==1||callback.params[0].type!=='Identifier')fail();
            const statements=callback.body.type==='BlockStatement'?callback.body.body:[{type:'ExpressionStatement',expression:callback.body}];
            for(const item of statements){if(item.type!=='ExpressionStatement')fail();const result=chain(item.expression,callback.params[0].name);validateProperties(result[1]);entries.push(result);}
        }
        return entries;
    }
    function groupSource(entries) {
        return "Rysm.model('group_modifier', event => {\n"+entries.map(([name,p])=>`  event.modify(${JSON.stringify(name)})${Object.entries(p).map(([key,value])=>`.${key}(${JSON.stringify(value)})`).join('')};`).join('\n')+'\n});\n';
    }
    function projectEntries() {
        const groups=Group.all.filter(g=>g.export&&(g.rysm_script_owned||Object.keys(props(g)).length));
        if(groups.some(g=>!g.name.trim()||Group.all.some(other=>other!==g&&other.export&&other.name===g.name)))throw new Error('脚本组名称不能为空或重复');
        return groups.map(g=>[qualifiedName(g.name),validateProperties(clone(props(g)))]);
    }
    function scriptSource(entries=projectEntries()) {return generatedHeader+groupSource(entries);}
    function mergeEntries(entries) {
        const merged=new Map();
        for(const [name,p] of entries)merged.set(name,mergeProperties(merged.get(name)||{},p));
        return merged;
    }
    function modelRoot() {
        if(typeof require!=='function')return null;
        const fs=require('fs'),path=require('path');let root=path.dirname(Project.save_path||Project.export_path||'');
        for(let i=0;i<12;i++){if(fs.existsSync(path.join(root,'ysm.json')))return root;const parent=path.dirname(root);if(parent===root)break;root=parent;}
        return null;
    }
    let scopeProject,scopePath,scopeChecked=0,scopeValue='';
    function modelScope() {
        const key=[Project.export_path,Project.save_path,Project.name].join('|');
        if(scopeProject===Project&&scopePath===key&&Date.now()-scopeChecked<1000)return scopeValue;
        scopeProject=Project;scopePath=key;scopeChecked=Date.now();
        const filename=(Project.export_path||Project.save_path||Project.name||'main').replace(/\\/g,'/').split('/').pop().replace(/\.(?:json|bbmodel)$/i,'');
        const root=modelRoot();
        if(root){try{const fs=require('fs'),path=require('path'),config=JSON.parse(fs.readFileSync(path.join(root,'ysm.json'),'utf8')),main=config.files?.player?.model?.main;
            const basename=file=>file.replace(/\\/g,'/').split('/').pop().replace(/\.json$/i,'');
            if(main&&basename(main)===filename)return scopeValue='';
            if(modelFilePaths(config.files).some(file=>file!==main&&basename(file)===filename))return scopeValue=filename;
        }catch{}}
        return scopeValue=filename==='main'||(!Project.export_path&&Project.save_path&&!/\.json$/i.test(Project.save_path))?'':filename;
    }
    function qualifiedName(name){const scope=modelScope();return scope?scope+'.'+name:name;}
    function modelFilePaths(value) {
        const paths=new Set();
        function collect(node){if(!node||typeof node!=='object')return;for(const [key,child] of Object.entries(node)){if(key==='model'){if(typeof child==='string')paths.add(child);else if(child&&typeof child==='object')Object.values(child).forEach(file=>{if(typeof file==='string')paths.add(file);});}else if(typeof child==='object')collect(child);}}
        collect(value);return [...paths];
    }
    let groupCatalogKey,groupCatalog=[];
    function availableGroupNames() {
        const current=Group.all.filter(g=>g.export).map(g=>qualifiedName(g.name)),root=modelRoot();if(!root)return current;
        const fs=require('fs'),path=require('path'),now=Math.floor(Date.now()/1000),key=root+now;
        if(key!==groupCatalogKey) {
            const config=JSON.parse(fs.readFileSync(path.join(root,'ysm.json'),'utf8')),paths=modelFilePaths(config.files),main=config.files?.player?.model?.main;
            groupCatalog=[];
            for(const file of paths) {
                const resolved=path.resolve(root,file);if(!resolved.startsWith(path.resolve(root)+path.sep)||!fs.existsSync(resolved)||fs.statSync(resolved).size>32*1024*1024)continue;
                const scope=file===main?'':path.basename(file,'.json'),tree=JSON.parse(fs.readFileSync(resolved,'utf8'));
                const bones=tree['minecraft:geometry']?.[0]?.bones||[];
                for(const bone of bones)if(typeof bone.name==='string')groupCatalog.push(scope?scope+'.'+bone.name:bone.name);
            }
            groupCatalogKey=key;
        }
        const prefix=modelScope();return [...new Set([...groupCatalog.filter(name=>prefix?!name.startsWith(prefix+'.'):name.includes('.')),...current])];
    }
    const geometryImports=new WeakMap();
    function restoreImportedGroupNames(groups,bones) {
        if(groups.length!==bones.length||new Set(bones.map(b=>b.name)).size!==bones.length||bones.some(b=>typeof b.name!=='string'||!/^[A-Za-z][A-Za-z0-9_.]*$/.test(b.name)))return false;
        const names=new Map(bones.map((b,i)=>[b.name,groups[i]]));
        if(Group.all.some(g=>!groups.includes(g)&&names.has(g.name)))return false;
        for(const bone of bones){const seen=new Set();let parent=bone;while(parent?.parent){if(seen.has(parent.name))return false;seen.add(parent.name);parent=bones.find(b=>b.name===parent.parent);}}
        if(!groups.some((group,i)=>group.name!==bones[i].name))return false;
        groups.forEach((group,i)=>{group.name=bones[i].name;});
        groups.forEach((group,i)=>{const parent=names.get(bones[i].parent)||'root';if(group.parent!==parent)group.addTo(parent);});
        return true;
    }
    function beforeGeometryImport(event) {
        if(!modelRoot()||!Array.isArray(event.model?.bones))return;
        geometryImports.set(Project,{before:new Set(Group.all),bones:event.model.bones.map(b=>({name:b.name,parent:b.parent}))});
    }
    function afterGeometryImport() {
        const state=geometryImports.get(Project);geometryImports.delete(Project);if(!state)return;
        if(restoreImportedGroupNames(Group.all.filter(g=>!state.before.has(g)),state.bones))inspectorKey=undefined;
    }
    function readManifest(folder) {
        const fs=require('fs'),path=require('path'),file=path.join(folder,'manifest.json');
        const manifest=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{version:1,scripts:[]};
        if(manifest.version!==1||!Array.isArray(manifest.scripts)||manifest.scripts.length>64||manifest.scripts.some(name=>!validScriptFile(name))||new Set(manifest.scripts).size!==manifest.scripts.length)throw new Error('RYSM 清单无效');
        return manifest;
    }
    function textBytes(source) {return typeof Buffer!=='undefined'?Buffer.byteLength(source,'utf8'):new TextEncoder().encode(source).length;}
    function checkSource(source) {if(typeof source!=='string'||textBytes(source)>1048576)throw new Error('脚本超过 1 MiB');return source;}
    function writeScriptTransaction(writes) {
        const fs=require('fs'),path=require('path'),before=new Map(),temps=[];
        for(const [file,source] of writes) {
            if(fs.existsSync(file)&&!(fs.lstatSync||fs.statSync)(file).isFile())throw new Error('脚本目标必须为普通文件：'+file);
            before.set(file,fs.existsSync(file)?fs.readFileSync(file):null);
            if(source!==null)checkSource(source);
        }
        try {
            for(const [file,source] of writes) {
                if(source===null){if(fs.existsSync(file))fs.unlinkSync(file);continue;}
                fs.mkdirSync(path.dirname(file),{recursive:true});
                const temp=file+'.'+Date.now()+'.tmp';temps.push(temp);fs.writeFileSync(temp,source,'utf8');fs.renameSync(temp,file);
            }
        }catch(error) {
            for(const [file,source] of before)try{if(source===null){if(fs.existsSync(file))fs.unlinkSync(file);}else fs.writeFileSync(file,source);}catch(e){error.message+='；恢复失败：'+file+' '+e.message;}
            throw error;
        }finally{for(const temp of temps)if(fs.existsSync(temp))fs.unlinkSync(temp);}
    }
    function writeScripts() {
        const root=modelRoot();
        if(!root){Blockbench.export({type:'RYSM JavaScript',extensions:['js'],name:'generated.blockbench',content:scriptSource()});return;}
        const fs=require('fs'),path=require('path'),folder=path.join(root,'rysm'),manifest=readManifest(folder),writes=new Map();
        const old='scripts/blockbench.js',target=path.join(folder,generatedFile),oldPath=path.join(folder,old);
        const hasOld=fs.existsSync(oldPath),hasNew=fs.existsSync(target);
        if(hasOld&&hasNew)throw new Error('同时存在 blockbench.js 和 generated.blockbench.js，请先手动合并；未写入任何文件');
        const existing=hasOld?checkSource(fs.readFileSync(oldPath,'utf8')):hasNew?checkSource(fs.readFileSync(target,'utf8')):null;
        const entries=mergeEntries(existing?generatedGroups(existing):[]);
        for(const [name,p] of projectEntries())entries.set(name,p);
        const owned=new Set(projectEntries().map(([name])=>name));
        const config=readGeneratedConfig(folder,manifest);
        let insertion=-1;
        for(const [migration,{source,declarations}] of config.files) {
            if(migration===generatedFile||migration===old)continue;
            const remaining=declarations.filter(([name])=>!owned.has(name));
            if(remaining.length!==declarations.length) {
                if(insertion<0)insertion=manifest.scripts.indexOf(migration);
                const migrationPath=path.join(folder,migration);
                writes.set(migrationPath+'.'+Date.now()+'.bak',source);
                writes.set(migrationPath,'// Migration registrations not edited in this project.\n'+groupSource(remaining));
                if(!remaining.length)manifest.scripts=manifest.scripts.filter(name=>name!==migration);
            }
        }
        if(hasOld) {
            writes.set(oldPath+'.'+Date.now()+'.bak',existing);
            writes.set(oldPath,null);
        }
        manifest.scripts=manifest.scripts.map(name=>name===old?generatedFile:name);
        if(!manifest.scripts.includes(generatedFile))manifest.scripts.splice(insertion<0?manifest.scripts.length:insertion,0,generatedFile);
        if(manifest.scripts.length>64)throw new Error('清单最多允许 64 个脚本');
        writes.set(target,scriptSource([...entries]));writes.set(path.join(folder,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
        writeScriptTransaction(writes);
    }
    const associations=new WeakMap();
    let associationProject,nextAssociationCheck=0,associationError='';
    function readGeneratedConfig(folder,manifest) {
        const fs=require('fs'),path=require('path'),files=new Map(),entries=[],sources=new Map();
        for(const name of manifest.scripts) {
            const file=path.join(folder,name);
            if(!fs.existsSync(file))continue;
            const source=checkSource(fs.readFileSync(file,'utf8'));
            if(!isGeneratedSource(source))continue;
            const declarations=generatedGroups(source);files.set(name,{source,declarations});
            for(const entry of declarations){entries.push(entry);sources.set(entry[0],name);}
        }
        return {files,merged:mergeEntries(entries),sources};
    }
    function associationNote(group) {
        const node=document.createElement('small'),state=associations.get(Project);
        node.textContent=state?.sources.get(qualifiedName(group.name))?'配置来源：'+state.sources.get(qualifiedName(group.name)):'项目结构化属性';
        if(state?.missing.length)node.textContent+='；未找到组：'+state.missing.join('、');
        return node;
    }
    function syncScriptGroups(force=false) {
        const root=modelRoot();if(!root)return;
        const fs=require('fs'),path=require('path'),folder=path.join(root,'rysm'),manifest=readManifest(folder);
        const signature=JSON.stringify([root,manifest,manifest.scripts.map(name=>{const file=path.join(folder,name);return fs.existsSync(file)?[fs.statSync(file).mtimeMs,fs.statSync(file).size]:null;}),modelScope(),Group.all.map(g=>[g.uuid,g.name,g.export])]);
        const previous=associations.get(Project);
        if(!force&&previous?.signature===signature)return previous;
        const config=readGeneratedConfig(folder,manifest),missing=[],duplicates=[],groups=[],catalog=new Set(availableGroupNames());
        for(const [name,value] of config.merged) {
            const matches=Group.all.filter(g=>g.export&&qualifiedName(g.name)===name);
            if(!matches.length){if(!catalog.has(name))missing.push(name);continue;}
            if(matches.length!==1){duplicates.push(name);continue;}
            const group=matches[0],baseline=previous?.baselines.get(group);
            if(group.locked)continue;
            if(force||baseline===JSON.stringify(props(group))||(!group.rysm_script_owned&&!Object.keys(props(group)).length))groups.push([group,value]);
        }
        if(force&&groups.length)Undo.initEdit({outliner:true,groups:groups.map(([g])=>g)});
        const baselines=new Map(previous?.baselines||[]);
        for(const [group,value] of groups){group.rysm_properties=clone(value);group.rysm_script_owned=true;baselines.set(group,JSON.stringify(value));}
        if(force&&groups.length)Undo.finishEdit('Import RYSM generated properties',{outliner:true,groups:groups.map(([g])=>g)});
        const state={signature,baselines,sources:config.sources,missing,duplicates};associations.set(Project,state);associationError='';inspectorKey=undefined;
        if(force||groups.length||missing.length||duplicates.length)Blockbench.showQuickMessage('RYSM 已关联 '+groups.length+' 个组'+(missing.length?'；未找到：'+missing.join('、'):'')+(duplicates.length?'；重名：'+duplicates.join('、'):''));
        return state;
    }
    function importScriptGroups() {
        if(!modelRoot())throw new Error('请先保存到含 ysm.json 的模型目录');
        return syncScriptGroups(true);
    }
    function scriptFiles() {
        const files={},root=modelRoot();
        if(root) {
            const fs=require('fs'),path=require('path'),folder=path.join(root,'rysm');
            for(const name of readManifest(folder).scripts)files[name]=checkSource(fs.readFileSync(path.join(folder,name),'utf8'));
        }
        for(const [name,source] of Object.entries(Project.rysm_scripts||{}))if(!Object.hasOwn(files,name))files[scriptPath(name)]=checkSource(source);
        if(!Object.hasOwn(files,generatedFile))files[generatedFile]=scriptSource();
        return files;
    }
    function saveUserScript(name,source,expectedSource) {
        scriptPath(name);checkSource(source);
        const root=modelRoot();
        if(root) {
            const fs=require('fs'),path=require('path'),folder=path.join(root,'rysm'),manifest=readManifest(folder),target=path.join(folder,name);
            const current=fs.existsSync(target)?fs.readFileSync(target,'utf8'):null;
            if(isGeneratedSource(current))throw new Error('生成文件只读；请通过组属性编辑');
            if(expectedSource!==undefined&&current!==expectedSource)throw new Error('脚本已被外部修改，请关闭并重新载入后合并');
            if(!manifest.scripts.includes(name))manifest.scripts.push(name);
            if(manifest.scripts.length>64)throw new Error('清单最多允许 64 个脚本');
            const writes=new Map();if(current!==null)writes.set(target+'.'+Date.now()+'.bak',current);
            writes.set(target,source);writes.set(path.join(folder,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');writeScriptTransaction(writes);
        }
        Project.rysm_scripts={...(Project.rysm_scripts||{}),[name]:source};Project.saved=false;
        if(!root)Blockbench.export({type:'RYSM JavaScript',extensions:['js'],name:name.split('/').pop().slice(0,-3),content:source});
    }
    function saveScripts(event) {
        if(!event?.saved||event.project!==Project||!supported()||!modelRoot())return;
        try{writeScripts();}catch(error){Blockbench.showQuickMessage('RYSM 保存失败：'+error.message);}
    }
    const scriptHints = [
        ["Rysm.model('group_modifier', event => {\n  event.modify('group').collider();\n});", '组效果事件 / 碰撞体'],
        ...['tick','pose','molang','cosmic','load'].map(event => [`Rysm.model('${event}', ${['tick','pose'].includes(event)?'game':'event'} => {\n  \n});`, event+' 事件']),
        ["modify('group')", '选择组，可继续链式调用'],
        ['physics({g:9.8, s:20, a:90, c:1, bc:true})', '物理骨参数'],
        ...['end','collider','facingCamera','glow','item'].map(key => [key+'()', '组效果；false 关闭']),
        ["cosmic({effect:'crosses', sc:'3388FF', sa:25})", '星空效果、RGB、混色比例'],
        ["outline({color:'auto', width:2})", '描边颜色、宽度 0–64'],
        ['frameAnimation({count:2, tick:6})', '按 tick 切换 UV 帧'],
        ["frameAnimation({count:2, macro:'rysmjs.frame', macroRepeat:false})", '宏帧动画'],
        ["register('value', game => game.entity.health, {fallback:0, sync:false})", 'Molang 注册'],
        ["register('crosses', {textures:['cross.png']})", '星空资源注册'],
        ['logKeyBindings()', '加载时列出快捷键'],
        ...['move(0, 0, 0)','rotate(0, 0, 0)','scale(1, 1, 1)'].map(text => [text, 'pose 组变换']),
        ["game.group('group')", 'pose 选择组'], ["game.molang('query.health')", '只读 Molang 查询'],
        ['game.model.state', '实例私有状态'], ['game.print(value)', '输出调试信息'],
        ["game.input.key('key.keyboard.escape')", '原始按键 down/pressed/released'],
        ["game.input.binding('key.inventory')", '游戏快捷键 down/pressed/released'],
        ["game.input.bindings('')", '筛选快捷键列表'],
        ["game.entity.attribute('minecraft:generic.movement_speed', 0)", '属性当前值、回退值'],
        ["game.entity.attributeBase('minecraft:generic.movement_speed', 0)", '属性基础值、回退值'],
        ...['health','maxHealth','position.x','position.y','position.z','velocity.x','velocity.y','velocity.z','pose','yaw','pitch','local'].map(key => ['game.entity.'+key, '实体数据']),
        ...['game.camera.yaw','game.camera.pitch','game.time.ticks','game.time.seconds','down','pressed','released'].map(key => [key, '运行时数据']),
        ...Object.entries(fields).map(([key, [label, value]]) => [key+': '+(key==='enable'||key==='disable'?"'rysmjs.toggle=1'":key==='gw'?"9.8":value), label]),
        ...[['kind',"'tail'"],['sc',"'3388FF'"],['ac',"'FFFFFF'"],['sa','25'],['aa','25'],['effect',"'crosses'"],['width','2'],['color',"'auto'"],['count','2'],['tick','6'],['macro',"'rysmjs.frame'"],['macroRepeat','false'],['fallback','0'],['sync','true'],['textures',"['cross.png']"]].map(([key,value]) => [key+': '+value, '效果 / 注册参数'])
    ];

    const modelEvents=['group_modifier','cosmic','molang','load','tick','pose'];
    function eventCall(node) {
        return node?.type==='CallExpression'&&memberName(node.callee)==='model'&&node.callee.object?.type==='Identifier'&&node.callee.object.name==='Rysm';
    }
    function walk(node, visit, context=null) {
        if(!node||typeof node.type!=='string')return;
        visit(node,context);
        for(const [key,value] of Object.entries(node)) {
            if(['loc','start','end'].includes(key))continue;
            if(Array.isArray(value))for(const child of value) {
                const callback=eventCall(node)&&child===node.arguments[1];
                walk(child,visit,callback?{type:node.arguments[0]?.value,parameter:child.params?.[0]?.name}:context);
            }
            else if(value&&typeof value==='object')walk(value,visit,context);
        }
    }
    function scriptAnalysis(source, groups=[]) {
        const diagnostics=[],colors=[],calls=[];let ast;
        const report=(node,message,severity='warning')=>diagnostics.push({line:node.loc.start.line-1,ch:node.loc.start.column,endLine:node.loc.end.line-1,endCh:node.loc.end.column,message,severity});
        try{ast=scriptParser.parse(source,{ecmaVersion:6,locations:true});}
        catch(error){return {diagnostics:[{line:(error.loc?.line||1)-1,ch:error.loc?.column||0,message:error.message,severity:'error'}],colors,calls};}
        walk(ast,(node,context)=>{
            if(node.type==='Literal'&&node.regex)report(node,'RYSM 不支持正则表达式','error');
            if(['ImportDeclaration','ExportNamedDeclaration','ExportDefaultDeclaration'].includes(node.type))report(node,'RYSM 不支持模块导入或导出','error');
            if(node.type==='Property'&&!node.computed&&['sc','ac','color'].includes(node.key.name??node.key.value)&&node.value.type==='Literal'&&typeof node.value.value==='string') {
                if(/^[\da-f]{6}$/i.test(node.value.value))colors.push({from:node.value.start+1,to:node.value.end-1,color:node.value.value});
                else if((node.key.name??node.key.value)==='color'&&node.value.value==='auto'){}
                else report(node.value,'颜色应为六位 RGB，不带 #；描边还支持 auto','error');
            }
            if(node.type!=='CallExpression')return;
            const method=memberName(node.callee),object=node.callee.object;
            if(object?.type==='Identifier'&&object.name==='Rysm') {
                if(method!=='model')report(node,'请使用新版 Rysm.model(event, callback)','error');
                else {
                    if(node.arguments[0]?.type==='Literal'&&!modelEvents.includes(node.arguments[0].value))report(node.arguments[0],'未知 RYSM 事件','error');
                    if(node.arguments.length!==2||!['ArrowFunctionExpression','FunctionExpression'].includes(node.arguments[1]?.type))report(node,'Rysm.model 需要事件名和回调函数','error');
                }
            }
            if(!context?.parameter)return;
            if(object?.type==='Identifier'&&object.name===context.parameter) {
                const expected=context.type==='group_modifier'?['modify']:['molang','cosmic'].includes(context.type)?['register']:context.type==='load'?['logKeyBindings']:['group','molang','print'];
                if(method&&!expected.includes(method))report(node,'此事件上下文没有 '+method+' 方法','error');
                if(method==='group'&&context.type!=='pose')report(node,'group() 仅可用于 pose 事件','error');
                if(['modify','group'].includes(method)&&node.arguments[0]?.type==='Literal'&&typeof node.arguments[0].value==='string'&&groups.length&&!groups.includes(node.arguments[0].value))report(node.arguments[0],'当前模型没有此可导出组');
                if(method==='modify'&&node.arguments[1]?.type==='ObjectExpression')try{validateProperties(literal(node.arguments[1]));}catch(error){if(!error.message.includes('手写逻辑'))report(node.arguments[1],error.message,'error');}
                if(method==='register'&&context.type==='molang') {
                    const name=node.arguments[0];
                    if(name?.type!=='Literal'||typeof name.value!=='string'||!/^[a-z_][a-z_0-9]*$/.test(name.value))report(name||node,'Molang 名称须为不带 rysmjs. 前缀的小写标识符','error');
                    const options=node.arguments[2];
                    if(options&&options.type!=='ObjectExpression')report(options,'Molang 选项必须为字面量对象，供服务端读取','error');
                    if(options?.type==='ObjectExpression')for(const p of options.properties) {
                        const key=p.key.name??p.key.value;
                        if(key==='sync'&&(p.value.type!=='Literal'||typeof p.value.value!=='boolean'))report(p,'sync 必须为布尔字面量','error');
                        if(key==='fallback')try{const value=literal(p.value);if(!['number','boolean'].includes(typeof value)||typeof value==='number'&&!Number.isFinite(value))throw Error();}catch{report(p,'fallback 必须为有限数值或布尔字面量','error');}
                    }
                }
            }
            function builder(n){return n?.type==='CallExpression'&&(memberName(n.callee)==='modify'&&n.callee.object?.name===context.parameter||effectKeys.includes(memberName(n.callee))&&builder(n.callee.object));}
            if(context.type==='group_modifier'&&builder(object)&&effectKeys.includes(method)) {
                const arg=node.arguments[0];
                if(arg)try{validateProperties({[method]:literal(arg)});}catch(error){if(!error.message.includes('手写逻辑'))report(arg,error.message,'error');}
                if(node.arguments.length>1)report(node,'组效果最多接受一个参数','error');
            }
            calls.push({from:node.start,to:node.end,method,context:context.type});
        });
        return {diagnostics,colors,calls};
    }
    function eventContext(source,cursor) {
        // Tokenization also works for unfinished member expressions. Comments and strings are never scanned as code.
        const tokens=[];
        try{for(const token of scriptParser.tokenizer(source.slice(0,cursor),{ecmaVersion:6}))tokens.push(token);}catch{}
        const stack=[];let context=null;
        for(let i=0;i<tokens.length;i++) {
            const t=tokens[i],label=t.type.label;
            if(label==='('||label==='{'||label==='[') {
                const model=label==='('&&tokens[i-3]?.value==='Rysm'&&tokens[i-2]?.type.label==='.'&&tokens[i-1]?.value==='model';
                let next=context;
                if(model) {
                    const event=tokens[i+1]?.value;
                    let j=i+3;if(tokens[j]?.value==='function')j++;
                    if(tokens[j]?.type.label==='(')j++;
                    next={type:event,parameter:tokens[j]?.value||(['tick','pose'].includes(event)?'game':'event')};
                }
                stack.push(context);context=next;
            }else if([')',']','}'].includes(label))context=stack.pop()||null;
        }
        return context;
    }
    function scriptCompletions(source,cursor,groups=[]) {
        const before=source.slice(0,cursor),context=eventContext(source,cursor),groupCall=/(?:modify|group)\(\s*(['"])([^'"\n]*)$/.exec(before);
        if(groupCall)return {from:cursor-groupCall[2].length,items:groups.filter(name=>name.startsWith(groupCall[2])).map(name=>({text:JSON.stringify(name).slice(1,-1).replace(/'/g,"\\'"),label:name+' · 模型组'}))};
        const eventName=/Rysm\.model\(\s*(['"])([^'"\n]*)$/.exec(before);
        if(eventName)return {from:cursor-eventName[2].length,items:modelEvents.filter(name=>name.startsWith(eventName[2])).map(text=>({text,label:text+' · 模型事件'}))};
        const token=/[\w.$]*$/.exec(before)[0],prefix=token.toLowerCase(),member=token.lastIndexOf('.'),suffix=token.slice(member+1).toLowerCase();
        const items=[];
        for(const [original,description] of scriptHints) {
            let text=original;
            if(context) {
                if(text.startsWith('game.')) {
                    if(!['tick','pose','molang'].includes(context.type))continue;
                    if(text.startsWith('game.group')&&context.type!=='pose')continue;
                    text=context.parameter+text.slice(4);
                }
                if(text.startsWith('modify(')&&context.type!=='group_modifier')continue;
                if(effectKeys.some(key=>text.startsWith(key+'('))&&context.type!=='group_modifier')continue;
                if(text.startsWith('register(')&&(!['cosmic','molang'].includes(context.type)||context.type==='cosmic'!==text.includes('textures:')))continue;
                if(text.startsWith('logKeyBindings(')&&context.type!=='load')continue;
                if(/^(move|rotate|scale)\(/.test(text)&&context.type!=='pose')continue;
            }
            if(text.toLowerCase().startsWith(prefix))items.push({text,label:text.split('\n')[0]+' · '+description});
            else if(member>=0&&!/^[$\w]+\./.test(text)&&text.toLowerCase().startsWith(suffix))items.push({text:token.slice(0,member+1)+text,label:text+' · '+description});
        }
        return {from:cursor-token.length,items};
    }
    const signatures={model:'Rysm.model(eventName, callback) · 事件：'+modelEvents.join(', '),modify:'event.modify(groupName, properties?) → 组效果链',
        physics:'physics({kind, r, g / gw, s, a, c, air, bc, enable, disable}) · kind: "tail" 使用尾骨默认值',
        cosmic:'cosmic({effect, sc, sa, ac, aa}) · RGB 六位无 #，混色 0–100',outline:'outline({color, width}) · color: RGB / auto；width: 0–64',
        frameAnimation:'frameAnimation({count, tick}) 或 {count, macro, macroRepeat} · 帧编号从 1 开始',register:'molang: register(name, callback, {fallback:0, sync:false})；cosmic: register(name, {textures:[...]})',
        group:'game.group(name) → move / rotate / scale，仅 pose 可用',rotate:'rotate(x, y, z) · 度数增量',move:'move(x, y, z) · 模型像素偏移',scale:'scale(x, y, z) · 倍率',
        molang:'game.molang(expression) · 只读 Molang 查询',attribute:'game.entity.attribute(id, fallback=0) · 属性当前值',attributeBase:'game.entity.attributeBase(id, fallback=0) · 属性基础值'};
    function parameterHint(source,cursor) {
        const tokens=[];try{for(const t of scriptParser.tokenizer(source.slice(0,cursor),{ecmaVersion:6}))tokens.push(t);}catch{}
        const stack=[];
        for(let i=0;i<tokens.length;i++) {
            const label=tokens[i].type.label;
            if(label==='(')stack.push(tokens[i-1]?.value);
            else if(label===')')stack.pop();
        }
        return [...stack].reverse().map(name=>signatures[name]).find(Boolean)||'Ctrl+Space 补全 · Ctrl+S 保存 · 仅静态诊断，不执行脚本';
    }
    const editorCSS=`
        .rysm-script-window{display:flex;flex-direction:column;gap:8px;min-width:0}
        .rysm-script-window .CodeMirror,.rysm-editor-hints{font-family:'Cascadia Mono','Cascadia Code',Consolas,'Lucida Console','Microsoft YaHei UI',monospace;font-variant-ligatures:none;line-height:1.55}
        .rysm-script-window .CodeMirror{height:52vh;min-height:240px;font-size:14px;background:var(--color-back,#20232d);color:var(--color-text,#eee);border:1px solid var(--color-border,#555)}
        .rysm-script-window .CodeMirror-gutters{background:var(--color-ui,#292d39);border-color:var(--color-border,#555)}
        .rysm-script-window .CodeMirror-linenumber{color:#a0abc1}
        .rysm-script-window .CodeMirror-cursor{border-color:var(--color-text,#eee)}
        .rysm-script-window .CodeMirror-selected{background:#456280!important}
        .rysm-script-window .CodeMirror-activeline-background{background:#8bb1ff12}
        .rysm-script-window .cm-keyword{color:#d8a3ff}.rysm-script-window .cm-string{color:#a6dc9f}
        .rysm-script-window .cm-number,.rysm-script-window .cm-atom{color:#f5c68a}.rysm-script-window .cm-comment{color:#8d9bb0}
        .rysm-script-window .cm-variable,.rysm-script-window .cm-property{color:#c8dfff}.rysm-script-window .cm-def{color:#82dedb}
        .rysm-script-window .cm-operator{color:#e4d5ff}.rysm-script-window .CodeMirror-matchingbracket{color:#fff!important;background:#467853}
        .rysm-diagnostics{max-height:120px;overflow:auto;font-size:12px}.rysm-diagnostics button{display:block;text-align:left;white-space:normal;width:100%}
        .rysm-error{border-bottom:2px wavy #ff7373}.rysm-warning{border-bottom:2px wavy #e9bb67}
        .rysm-diagnostic-gutter{width:14px}.rysm-script-toolbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
        .rysm-script-status,.rysm-parameter-hint{white-space:pre-wrap;font-size:12px;overflow-wrap:anywhere}
        .rysm-color-chip{display:inline-block;width:12px;height:12px;border:1px solid #aaa;vertical-align:middle;margin-right:3px;cursor:pointer;padding:0;min-width:0;min-height:0}
        .rysm-editor-hints{z-index:100000!important;background:#252936;color:#eef1f9;border-color:#626f88;max-width:700px}
        .rysm-editor-hints li.CodeMirror-hint-active{background:#385e7e;color:white}
    `;
    function attachScriptEditor(host,source,options={}) {
        const CodeMirror=loadCodeMirror(),style=document.createElement('style');
        style.textContent=editorBaseCSS+editorCSS;document.head.append(style);
        const holder=document.createElement('div');host.classList.add('rysm-script-window');host.append(holder);
        const hint=document.createElement('div');hint.className='rysm-parameter-hint';hint.setAttribute('role','status');
        const diagnostics=document.createElement('div');diagnostics.className='rysm-diagnostics';diagnostics.setAttribute('aria-label','脚本诊断');host.append(hint,diagnostics);
        const groups=()=>typeof options.groups==='function'?options.groups():options.groups||[];
        function complete(cm) {
            if(cm.getOption('readOnly')||composing)return;
            cm.showHint({completeSingle:false,hint(editor){
                const cursor=editor.indexFromPos(editor.getCursor()),results=scriptCompletions(editor.getValue(),cursor,groups());
                return {from:editor.posFromIndex(results.from),to:editor.getCursor(),list:results.items.map(item=>({text:item.text,displayText:item.label}))};
            }});
            const menu=document.querySelector('.CodeMirror-hints');menu?.classList.add('rysm-editor-hints');
        }
        const cm=CodeMirror(holder,{value:source,mode:'javascript',lineNumbers:true,styleActiveLine:true,matchBrackets:true,indentUnit:2,tabSize:2,lineWrapping:false,
            readOnly:!!options.readOnly,gutters:['CodeMirror-linenumbers','rysm-diagnostic-gutter'],
            extraKeys:{'Ctrl-Space':complete,'Cmd-Space':complete,'Ctrl-S':()=>options.save?.(),'Cmd-S':()=>options.save?.(),Tab:editor=>{if(editor.somethingSelected())editor.indentSelection('add');else editor.replaceSelection('  ','end');},'Shift-Tab':editor=>editor.indentSelection('subtract')}
        });
        let timer,marks=[],pickers=[],composing=false,disposed=false;
        const updateHint=()=>{hint.textContent=parameterHint(cm.getValue(),cm.indexFromPos(cm.getCursor()));};
        const analyze=()=>{
            if(disposed||composing)return;
            for(const mark of marks)mark.clear();marks=[];
            for(const picker of pickers)picker.remove();pickers=[];
            cm.clearGutter('rysm-diagnostic-gutter');diagnostics.replaceChildren();
            const analysis=scriptAnalysis(cm.getValue(),groups()),byLine=new Map();
            for(const item of analysis.diagnostics) {
                const from={line:item.line,ch:item.ch},to={line:item.endLine??item.line,ch:item.endCh??item.ch+1};
                if(cm.indexFromPos(from)<cm.indexFromPos(to))marks.push(cm.markText(from,to,{className:'rysm-'+item.severity,title:item.message}));
                if(!byLine.has(item.line))byLine.set(item.line,[]);byLine.get(item.line).push(item);
                const button=document.createElement('button');button.type='button';button.textContent=`${item.line+1}:${item.ch+1} ${item.severity==='error'?'错误':'提示'} · ${item.message}`;
                button.onclick=()=>{cm.setCursor(from);cm.scrollIntoView(from,60);cm.focus();};diagnostics.append(button);
            }
            for(const [line,items] of byLine) {
                const marker=document.createElement('span');marker.textContent='●';marker.style.color=items.some(i=>i.severity==='error')?'#ff7373':'#e9bb67';marker.title=items.map(i=>i.message).join('\n');cm.setGutterMarker(line,'rysm-diagnostic-gutter',marker);
            }
            for(const color of analysis.colors) {
                const chip=document.createElement('button');chip.type='button';chip.className='rysm-color-chip';chip.style.backgroundColor='#'+color.color;chip.title='RGB '+color.color;chip.setAttribute('aria-label','选择 RGB 颜色 '+color.color);
                const mark=cm.setBookmark(cm.posFromIndex(color.from-1),{widget:chip,insertLeft:true});marks.push(mark);
                if(!cm.getOption('readOnly')) {
                    const input=document.createElement('input');input.type='color';input.value='#'+color.color;input.style.cssText='position:fixed;left:-10000px;width:1px;height:1px';document.body.append(input);pickers.push(input);
                    chip.onclick=event=>{event.preventDefault();input.click();};
                    input.onchange=()=>{const position=mark.find();if(!position)return;const start=cm.indexFromPos(position)+1;cm.replaceRange(input.value.slice(1).toUpperCase(),cm.posFromIndex(start),cm.posFromIndex(start+6),'+color');};
                }else chip.disabled=true;
            }
            if(!analysis.diagnostics.length)diagnostics.textContent='静态检查未发现问题；仍需在游戏中验证 Rhino 与模型行为。';
            updateHint();options.onAnalysis?.(analysis);
        };
        cm.on('change',()=>{options.change?.(cm.getValue());clearTimeout(timer);timer=setTimeout(analyze,250);});
        cm.on('cursorActivity',updateHint);
        cm.on('inputRead',(_editor,change)=>{if(!composing&&change.origin==='+input'&&/[\w.$]$/.test(change.text.join(''))&&/[\w.$]{2}$/.test(cm.getLine(cm.getCursor().line).slice(0,cm.getCursor().ch)))complete(cm);});
        const wrapper=cm.getWrapperElement();
        const compositionStart=()=>{composing=true;cm.closeHint();};
        const compositionEnd=()=>{composing=false;clearTimeout(timer);timer=setTimeout(analyze,250);};
        const stopKeys=event=>{if(event.key!=='Escape')event.stopPropagation();};
        wrapper.addEventListener('compositionstart',compositionStart,true);wrapper.addEventListener('compositionend',compositionEnd,true);wrapper.addEventListener('keydown',stopKeys);
        analyze();setTimeout(()=>{if(!disposed)cm.refresh();},0);
        return {cm,analyze,complete:()=>complete(cm),dispose(){disposed=true;clearTimeout(timer);cm.closeHint();for(const mark of marks)mark.clear();for(const picker of pickers)picker.remove();wrapper.removeEventListener('compositionstart',compositionStart,true);wrapper.removeEventListener('compositionend',compositionEnd,true);wrapper.removeEventListener('keydown',stopKeys);cm.getWrapperElement().remove();hint.remove();diagnostics.remove();holder.remove();style.remove();}};
    }
    function cosmicRegistrationSource(name,textureText) {
        name=String(name).trim();
        validateProperties({cosmic:{effect:name}});
        if(name.includes(':'))throw new Error('效果名称不能包含冒号');
        const textures=String(textureText).split(/\r?\n/).map(value=>value.trim()).filter(Boolean);
        if(!textures.length||textures.length>64)throw new Error('请填写 1–64 个 PNG 贴图路径，每行一个');
        for(const texture of textures)if(!texture.endsWith('.png')||/[\\\x00-\x1f:]/.test(texture)||texture.split('/').some(part=>!part||part==='.'||part==='..'))throw new Error('贴图必须是效果目录内的相对 PNG 路径：'+texture);
        return '// 贴图放入模型目录 rysm/cosmic/'+name+'/（顺序与下方列表一致）。\n'+
            "Rysm.model('cosmic', event => {\n  event.register("+JSON.stringify(name)+', '+JSON.stringify({textures})+');\n});\n'+
            '// 在组属性的「星空效果 → 效果名称」中填写 '+name+'。\n';
    }
    function editScripts(createNew=false) {
        if(scriptEditor){scriptEditor.dialog.show();scriptEditor.editor.cm.refresh();if(createNew)scriptEditor.createNew();return;}
        const project=Project,files=scriptFiles(),saved={...files},diskSources={};
        if(modelRoot())for(const [name,source] of Object.entries(files))diskSources[name]=source;
        for(const [name,source] of Object.entries(project.rysm_script_drafts||{}))if(!readOnlyScript(name,files[name])){scriptPath(name);files[name]=checkSource(source);}
        if(!createNew&&!Object.keys(files).some(name=>!readOnlyScript(name,files[name])))files['scripts/main.js']="// 高级逻辑写在此文件。此文件不会由属性表单覆盖。\nRysm.model('tick', game => {\n  \n});\n";
        let current=Object.keys(files).find(name=>!readOnlyScript(name,files[name]))||generatedFile,detached=false,loading=false;
        const host=document.createElement('div'),toolbar=document.createElement('div'),select=document.createElement('select'),nameInput=document.createElement('input'),status=document.createElement('div');
        host.className='rysm-script-window';toolbar.className='rysm-script-toolbar';status.className='rysm-script-status';status.setAttribute('role','status');
        select.setAttribute('aria-label','脚本文件');nameInput.setAttribute('aria-label','新脚本路径');nameInput.placeholder='scripts/main.js';nameInput.style.width='220px';
        const add=document.createElement('button');add.type='button';add.textContent='新建';
        const cosmic=document.createElement('button');cosmic.type='button';cosmic.textContent='注册星空效果';toolbar.append(select,nameInput,add,cosmic);host.append(toolbar,status);
        const dirty=name=>!readOnlyScript(name,files[name])&&files[name]!==saved[name];
        function refreshList() {
            select.replaceChildren();for(const name of Object.keys(files)){const option=document.createElement('option');option.value=name;option.textContent=(dirty(name)?'* ':'')+name+(readOnlyScript(name,files[name])?'（只读）':'');select.append(option);}select.value=current;
        }
        function save() {
            try {
                if(detached||Project!==project)throw new Error('原项目已切换；当前窗口保留草稿，请使用导出草稿');
                if(readOnlyScript(current,files[current]))throw new Error('生成文件只读；请通过组属性编辑');
                saveUserScript(current,files[current],Object.hasOwn(diskSources,current)?diskSources[current]:modelRoot()?null:undefined);
                saved[current]=files[current];diskSources[current]=files[current];
                if(project.rysm_script_drafts)delete project.rysm_script_drafts[current];
                refreshList();status.textContent='已保存 '+current;
                return true;
            }catch(error){status.textContent=error.message;return false;}
        }
        function switchTo(name) {
            current=name;loading=true;editor.cm.setOption('readOnly',detached||readOnlyScript(name,files[name]));editor.cm.setValue(files[name]);loading=false;
            editor.cm.clearHistory();editor.analyze();refreshList();status.textContent=detached?'原项目已切换，草稿仍保留，可导出。':readOnlyScript(name,files[name])?'自动生成文件只读；高级内容请写入独立脚本。':'手写脚本独立保存，不在 Blockbench 执行。';
        }
        function confirmDirty(action,all=false) {
            const pending=all?Object.keys(files).filter(dirty):dirty(current)?[current]:[];
            if(!pending.length){action();return;}
            Blockbench.showMessageBox({title:'未保存的脚本',message:pending.join('\n')+'\n切换文件会保留窗口内草稿；关闭窗口会丢失未保存内容。',buttons:[all?'返回编辑':'保留草稿并切换','保存后继续',all?'丢弃并关闭':'返回编辑'],confirm:0,cancel:all?0:2},index=>{
                if(index===0&&!all)action();
                if(index===1){const original=current;for(const name of pending){current=name;if(!save()){current=original;refreshList();return;}}current=original;action();}
                if(index===2&&all){for(const name of pending)if(project.rysm_script_drafts)delete project.rysm_script_drafts[name];action();}
            });
        }
        function close(){confirmDirty(()=>{editor.dispose();dialog.hide();scriptEditor=undefined;},true);}
        const dialog=new Dialog({id:'rysm_script_editor',title:'RYSM JavaScript 编辑器',width:1000,lines:[host],buttons:['保存','导出草稿','关闭'],confirmIndex:-1,cancelIndex:2,
            onButton(index){if(index===0)save();if(index===1){if(readOnlyScript(current,files[current])){status.textContent='生成文件请使用写入模型 rysm 文件夹导出';return false;}Blockbench.export({type:'JavaScript',extensions:['js'],name:current.split('/').pop().slice(0,-3),content:files[current]});}if(index===2)close();return false;},
            onCancel(){close();return false;}
        });
        refreshList();dialog.show();
        const editor=attachScriptEditor(host,files[current],{groups:()=>Project===project?availableGroupNames():[],readOnly:readOnlyScript(current,files[current]),save,
            change(source){if(loading)return;files[current]=source;if(!readOnlyScript(current,files[current])){project.rysm_script_drafts={...(project.rysm_script_drafts||{}),[current]:source};project.saved=false;}refreshList();}});
        select.onchange=()=>{const next=select.value;select.value=current;confirmDirty(()=>switchTo(next));};
        add.onclick=()=>{try{if(detached)throw new Error('原项目已切换');const name=scriptPath(nameInput.value.trim());if(Object.hasOwn(files,name))throw new Error('脚本文件已存在');confirmDirty(()=>{files[name]="// RYSM model script\nRysm.model('group_modifier', event => {\n  // Ctrl+Space 补全组名和效果；RGB 字面量可点选颜色。\n});\n";project.rysm_script_drafts={...(project.rysm_script_drafts||{}),[name]:files[name]};project.saved=false;switchTo(name);});}catch(error){status.textContent=error.message;}};
        cosmic.onclick=()=>{
            if(detached){status.textContent='原项目已切换';return;}
            const registration=new Dialog({id:'rysm_cosmic_registration',title:'注册星空效果',form:{
                name:{label:'效果名称',type:'text',value:'crosses'},
                textures:{label:'PNG 路径（每行一个）',type:'textarea',value:'cross.png'},
                help:{type:'info',text:'贴图放在模型 rysm/cosmic/<效果名称>/ 下；这里只生成注册脚本，不复制贴图。创建后保存脚本，并在组属性的星空效果中填写相同效果名称。'}
            },onConfirm(values){
                try {
                    if(detached||Project!==project)throw new Error('原项目已切换');
                    const source=cosmicRegistrationSource(values.name,values.textures);
                    confirmDirty(()=>{
                        let index=1,name='scripts/cosmic.js';while(Object.hasOwn(files,name))name='scripts/cosmic'+(++index)+'.js';
                        files[name]=source;project.rysm_script_drafts={...(project.rysm_script_drafts||{}),[name]:source};project.saved=false;switchTo(name);
                    });
                }catch(error){Blockbench.showQuickMessage(error.message);return false;}
            }});registration.show();
        };
        scriptEditor={project,dialog,editor,registerCosmic(){cosmic.click();},createNew(){let index=1,name='scripts/custom.js';while(Object.hasOwn(files,name))name='scripts/custom'+(++index)+'.js';nameInput.value=name;add.click();},preserve(){for(const name of Object.keys(files).filter(dirty))project.rysm_script_drafts={...(project.rysm_script_drafts||{}),[name]:files[name]};},detach(){if(detached)return;this.preserve();detached=true;editor.cm.setOption('readOnly',true);status.textContent='原项目已切换。草稿仍保留，请导出后重新打开编辑器。';},dispose(){editor.dispose();dialog.hide();}};
        status.textContent='手写脚本独立保存，不执行。* 表示未保存草稿。';if(createNew)scriptEditor.createNew();
    }
    const api={restoreImportedGroupNames,modelScope,qualifiedName,availableGroupNames,projectEntries,physicsChain,physicsForm,applyChainProperties,editPhysicsChain,editScriptProperties,props,isPhysics,physicsKind,inheritValues,effectivePhysics,validateProperties,setProperties,createPhysics,removePhysics,physicsTopology,
        sectorLines,capsuleLines,cosmicRegistrationSource,scriptCompletions,eventContext,scriptAnalysis,parameterHint,attachScriptEditor,generatedGroups,groupSource,scriptSource,scriptPath,
        syncScriptGroups,readGeneratedConfig,generatedFile,readOnlyScript,writeScripts,saveScripts,saveUserScript,scriptFiles,importScriptGroups,writeScriptTransaction,editScripts,updateOverlay,disposeOverlay,
        pickPhysics,finishPhysicsPick,hitCollider,selectionKey};
    if(typeof module!=='undefined'&&module.exports)module.exports=api;
    if(typeof Plugin==='undefined')return;
    function action(callback){return ()=>{try{callback();}catch(error){Blockbench.showQuickMessage(error.message);}};}
    Plugin.register('reimagined_ysm_physics_groups',{
        title:'Reimagined YSM 编辑器',author:'Reimagined YSM contributors',icon:'tune',version:'4.1.1',variant:'both',min_version:'5.0.0',
        description:'新版 RYSM JS 组属性、离线脚本编辑器，以及编辑模式物理骨链与碰撞体积辅助。',
        onload(){
            scriptProperty=new Property(Group,'object','rysm_properties',{default:{}});
            scriptOwnedProperty=new Property(Group,'boolean','rysm_script_owned',{default:false});
            scriptProjectProperty=new Property(ModelProject,'object','rysm_scripts',{default:{}});
            scriptDraftProperty=new Property(ModelProject,'object','rysm_script_drafts',{default:{}});
            Blockbench.on('saved_state_changed',saveScripts);
            for(const codec of [Codecs.bedrock,Codecs.bedrock_old]){codec?.on('parse',beforeGeometryImport);codec?.on('parsed',afterGeometryImport);}
            panel=makeFloatingInspector();
            menu=new BarMenu('openysm_physics',[
                {name:'打开组属性检查器',icon:'icon-vertexsnap',click:showPanel},
                {name:'编辑组属性',icon:'tune',click:action(()=>editScriptProperties())},
                {name:'JavaScript 编辑器',icon:'code',click:action(editScripts)},
                {name:'注册星空效果',icon:'auto_awesome',click:action(()=>{editScripts();scriptEditor.registerCosmic();})},
                {name:'导入生成脚本属性',icon:'download',click:action(importScriptGroups)},
                {name:'写入模型 rysm 文件夹',icon:'save',click:action(writeScripts)}
            ],{name:'Reimagined YSM',condition:()=>supported()&&Modes.edit});
            window.addEventListener('mousedown',pickPhysics,true);window.addEventListener('mouseup',finishPhysicsPick,true);window.addEventListener('blur',clearPickedGesture);
            MenuBar.update();frame=requestAnimationFrame(tick);
        },
        onunload(){
            // Preserve unsaved text in its owning project even if a reload closes the window.
            if(scriptEditor){scriptEditor.preserve?.();scriptEditor.dispose();scriptEditor=undefined;}
            Blockbench.removeListener('saved_state_changed',saveScripts);
            for(const codec of [Codecs.bedrock,Codecs.bedrock_old]){codec?.removeListener('parse',beforeGeometryImport);codec?.removeListener('parsed',afterGeometryImport);}
            scriptDraftProperty?.delete();scriptProjectProperty?.delete();scriptOwnedProperty?.delete();scriptProperty?.delete();cancelAnimationFrame(frame);
            window.removeEventListener('mousedown',pickPhysics,true);window.removeEventListener('mouseup',finishPhysicsPick,true);window.removeEventListener('blur',clearPickedGesture);
            clearPickedGesture();disposeOverlay();clearGizmos();menu?.delete();panel?.delete();MenuBar.update();
        }
    });
})();
