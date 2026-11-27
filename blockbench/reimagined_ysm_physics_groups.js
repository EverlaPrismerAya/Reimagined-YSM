/* global Plugin, BarMenu, MenuBar, THREE, Cube, Group, Animation, Project, Format, Undo, Modes, Blockbench, Canvas, Transformer, settings */
(() => {
    'use strict';
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
    const joint = /^(?:ysmPhys|ysmTail)(?:#([^@]+))?@([^@]+)$/;
    const marker = /^(?:ysmPhys(?:End|C)|ysmItem)@[^@]+$/;
    const end = /^ysmPhysEnd@[^@]+$/;
    const float = text => decimal.test(text) && Number.isFinite(Math.fround(Number(text)));

    function validate(key, value) {
        if (key === 'enable' || key === 'disable') {
            const items = value.split(',').map(s => s.trim());
            if (items.every(s => /^[^\s(),=<>!@]+$/.test(s)) || items.every(s => {
                const match = comparison.exec(s);
                return match && /^(?:v|variable|rysmjs)\./.test(s) && float(match[1]);
            })) return;
        } else if (key === 'bc' ? /^(true|false)$/.test(value) : float(value) &&
            (key === 'g' || key === 'gw' || Math.fround(+value) >= 0) && (key !== 'a' || Math.fround(+value) <= 180)) return;
        throw new Error(`${fields[key][0]}：值无效。`);
    }

    function validateGravity(values) {
        if (values.g && values.gw) throw new Error('重力与重力阱不能同时设置。');
    }

    function read(name) {
        const match = joint.exec(name);
        if (!match) {
            if (name.startsWith('ysmPhys') || name.startsWith('ysmTail')) throw new Error('不是可编辑的物理组，或物理组名称无效。');
            return {name, values: {}};
        }
        const values = {};
        for (const entry of match[1]?.split(/,(?![^()]*\))/) ?? []) {
            const pair = /^\s*(\w+)\s*=\s*(.*?)\s*$/.exec(entry);
            if (!pair || !Object.hasOwn(fields, pair[1]) || Object.hasOwn(values, pair[1])) throw new Error('物理参数未知、重复或格式无效。');
            let [, key, value] = pair;
            if (key === 'enable' || key === 'disable') {
                if (!/^\([^()]*\)$/.test(value)) throw new Error(`${key} 必须使用括号。`);
                value = value.slice(1, -1);
            }
            validate(key, value);
            values[key] = value;
        }
        validateGravity(values);
        return {name: match[2], values, kind:name.startsWith('ysmTail') ? 'ysmTail' : 'ysmPhys'};
    }

    function encode(name, values, kind = 'ysmPhys') {
        if (!['ysmPhys', 'ysmTail'].includes(kind)) throw new Error('未知物理骨类型。');
        name = name.trim();
        if (!name || /[@\r\n]/.test(name)) throw new Error('名称不能为空，也不能包含 @ 或换行。');
        validateGravity(values);
        const entries = Object.entries(values).filter(([, value]) => value).map(([key, value]) => {
            validate(key, value);
            return `${key}=${key === 'enable' || key === 'disable' ? `(${value})` : value}`;
        });
        return `${kind}${entries.length ? '#' + entries.join(',') : ''}@${name}`;
    }

    const effectFields = {
        ysmCosmic: {sc:['空间颜色 RGB', ''], sa:['空间混色 (%)', '0'], ac:['星体颜色 RGB', ''], aa:['星体混色 (%)', '0']},
        ysmOutline: {color:['描边颜色 RGB / auto', 'auto'], width:['描边宽度 (像素)', '2']}
    };
    const effect = /^(ysmCosmic|ysmOutline)(?:#([^@]+))?(?:@([^@]+))?$/;
    function readEffect(text) {
        const match = effect.exec(text);
        if (!match) throw new Error('星空或描边组名称无效。');
        const values = {};
        for (const entry of match[2]?.split(',') ?? []) {
            const pair = entry.trim().split('=').map(value => value.trim());
            if (pair.length !== 2 || Object.hasOwn(values, pair[0])) throw new Error('参数格式无效或重复。');
            values[pair[0]] = pair[1];
        }
        const name = match[3] || (match[1] === 'ysmCosmic' ? '星空' : '描边');
        encodeEffect(name, values, match[1]);
        return {name, values, kind:match[1]};
    }
    function encodeEffect(name, values, kind) {
        if (!Object.hasOwn(effectFields, kind)) throw new Error('未知效果类型。');
        name = name.trim();
        if (!name || /[@\r\n]/.test(name)) throw new Error('名称不能为空，也不能包含 @ 或换行。');
        const entries = [];
        for (const [key, raw] of Object.entries(values)) {
            const value = String(raw).trim();
            if (!Object.hasOwn(effectFields[kind], key)) throw new Error('未知效果参数。');
            if (!value) continue;
            const color = ['sc', 'ac', 'color'].includes(key);
            if (color ? !(/^[0-9a-f]{6}$/i.test(value) || key === 'color' && value === 'auto') :
                !float(value) || +value < 0 || +value > (key === 'width' ? 64 : 100)) throw new Error(`${effectFields[kind][key][0]}：值无效。`);
            entries.push(`${key}=${value}`);
        }
        return `${kind}${entries.length ? '#' + entries.join(',') : ''}@${name}`;
    }

    function editEffect(bone, kind, create = false) {
        const project = Project;
        let oldName = scriptName(bone);
        const model = create ? {name:oldName, values:{}} : readEffect(oldName);
        const root = document.createElement('div');
        const title = document.createElement('h3');
        title.textContent = kind === 'ysmCosmic' ? '星空着色器组' : '描边组';
        root.append(title);
        function control(label, value) {
            const row = document.createElement('label');
            row.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:8px;align-items:center;margin:8px 0';
            const input = document.createElement('input');
            input.style.cssText = 'width:100%;min-width:0';
            input.value = value;
            row.append(label, input); root.append(row);
            return input;
        }
        const name = control('组名称', model.name);
        const inputs = {};
        for (const [key, [label, fallback]] of Object.entries(effectFields[kind])) {
            inputs[key] = control(label, model.values[key] || '');
            inputs[key].placeholder = fallback || '六位 RGB，例如 00ffca';
        }
        const help = document.createElement('p');
        help.textContent = '留空使用该效果默认值。效果应用于组内所有后代；组合效果请使用父子组嵌套。实际效果需在游戏中查看。';
        const preview = document.createElement('code');
        preview.style.cssText = 'display:block;overflow-wrap:anywhere';
        const status = document.createElement('p'); status.setAttribute('role', 'status');
        const proposed = () => encodeEffect(name.value, Object.fromEntries(Object.entries(inputs).map(([key, input]) => [key,input.value])), kind);
        root.oninput = () => { try { preview.textContent = proposed(); status.textContent = ''; commit(false); } catch (error) { status.textContent = error.message; } };
        const commit = remove => {
            try {
                if (Project !== project || Group.first_selected !== bone || scriptName(bone) !== oldName || bone.locked || !bone.export) throw new Error('目标骨骼已改变，请重新选择。');
                const next = uniqueName(remove ? name.value.trim() : proposed(), bone);
                if (next === scriptName(bone)) return;
                Undo.initEdit({outliner:true, groups:[bone]});
                setScriptName(bone, next);
                Undo.finishEdit('Edit Reimagined YSM group effect', {outliner:true, groups:[bone]});
                oldName = scriptName(bone);
                inspectorKey = remove || create ? undefined : selectionKey(bone);
            } catch (error) { status.textContent = error.message; }
        };
        root.append(help, preview);
        if (!create) {
            const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '取消效果标记';
            remove.onclick = () => commit(true); root.append(remove);
        }
        root.append(status); root.oninput();
        panel.node.replaceChildren(root);
    }

    function inheritValues(parent, local) {
        const values = {...parent, ...local};
        if (Object.hasOwn(local, 'gw')) delete values.g;
        else if (Object.hasOwn(local, 'g')) delete values.gw;
        return values;
    }

    function inherited(bone) {
        if (!(bone instanceof Group) || !joint.test(scriptName(bone))) return {};
        const tail = scriptName(bone).startsWith('ysmTail');
        const parent = bone.parent instanceof Group && scriptName(bone.parent).startsWith('ysmTail') === tail ? inherited(bone.parent) : {};
        return inheritValues(parent, read(scriptName(bone)).values);
    }

    let menu, panel, frame, inspectorKey, draw, gizmos, originalSanitize, sanitize;
    const supported = () => ['bedrock', 'bedrock_old'].includes(Format?.id);
    function uniqueName(base, except) {
        let name = base;
        for (let i = 2; Group.all.some(group => group !== except && scriptName(group).toLowerCase() === name.toLowerCase()); i++) {
            name = `${base}_${i}`;
        }
        return name;
    }

    const minLength = 16e-6; // Runtime squared distance threshold is in blocks.
    const distance = (a, b) => Math.hypot(...a.map((n, i) => n - b[i]));
    const defaultEnd = origin => origin.map((n, i) => n - (i === 1 ? 4 : 0));

    function cancelPhysicsButton(bone, status) {
        const project = Project;
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = '取消物理化';
        button.onclick = () => {
            if (Project !== project || Group.first_selected !== bone || bone.locked) return;
            const name = joint.exec(scriptName(bone))?.[2];
            if (!name) return;
            const normalName = uniqueName(name.startsWith('ysmPhys') ? `bone_${name}` : name, bone);
            const ends = bone.children.filter(child => child instanceof Group && end.test(scriptName(child)));
            const parent = bone.parent;
            const repair = parent instanceof Group && joint.test(scriptName(parent)) && !parent.children.some(child =>
                child !== bone && child instanceof Group && child.export && (joint.test(scriptName(child)) || end.test(scriptName(child))));
            if (ends.some(child => child.locked) || (repair && parent.locked)) {
                status.textContent = '请先解锁受影响的末端或父物理组。';
                return;
            }
            const removable = ends.filter(child => !child.children.length && !Animation.all.some(animation =>
                animation.animators[child.uuid]?.keyframes?.length));
            const animations = Animation.all.filter(animation => removable.some(child => animation.animators[child.uuid]));
            const groups = [bone, ...ends.filter(child => !removable.includes(child))];
            Undo.initEdit({outliner:true, groups:[bone, ...ends], selection:true, animations});
            setScriptName(bone, normalName);
            for (const child of ends) {
                if (removable.includes(child)) { child.remove(false); continue; }
                const label = scriptName(child).slice('ysmPhysEnd@'.length);
                setScriptName(child, uniqueName(label.startsWith('ysmPhys') ? `bone_${label}` : label, child));
            }
            if (repair) {
                const pivot = distance(parent.origin, bone.origin) > minLength ? bone.origin.slice() : defaultEnd(parent.origin);
                const name = uniqueName(`ysmPhysEnd@${joint.exec(scriptName(parent))[2]}_end`);
                groups.push(scriptify(new Group({name, origin:pivot}).addTo(parent).init()));
            }
            Undo.finishEdit('Remove Reimagined YSM physics', {outliner:true, groups, selection:true, animations});
            inspectorKey = undefined;
        };
        return button;
    }

    function edit(bone, create = false, kind = 'ysmPhys') {
        const project = Project;
        let oldName = scriptName(bone);
        const model = read(oldName);
        const parentValues = inherited(bone.parent);
        const children = bone.children.filter(child => child instanceof Group);
        const branches = children.filter(child => joint.test(scriptName(child)));
        const ends = children.filter(child => end.test(scriptName(child)));
        const endpoint = ends[0];
        if (branches.some(child => !child.export) || (endpoint && (!endpoint.export || endpoint.locked))) {
            throw new Error('请先将物理子组设为可导出，并确保现有末端可导出、未锁定。');
        }
        if (ends.length > 1 || endpoint?.children.length || (endpoint && branches.length)) {
            throw new Error('请先修正拓扑：分叉组不能有末端，叶节点只能有一个空末端组。');
        }
        const parentEnds = joint.test(scriptName(bone.parent)) ? bone.parent.children.filter(child => child instanceof Group && end.test(scriptName(child))) : [];
        const movedEnd = create && !endpoint && !branches.length && parentEnds.length === 1 ? parentEnds[0] : null;
        if (parentEnds.length && (!movedEnd || movedEnd.locked || !movedEnd.export || movedEnd.children.length || bone.parent.locked ||
            bone.parent.children.some(child => child !== bone && joint.test(scriptName(child))))) {
            throw new Error('无法接入：父物理组必须至多有一个可移动的空末端，且不能同时有其他物理子组。');
        }
        const origin = bone.origin.slice();
        if (branches.some(child => distance(origin, child.origin) <= minLength) ||
            (joint.test(scriptName(bone.parent)) && distance(origin, bone.parent.origin) <= minLength)) {
            throw new Error('物理链的相邻枢轴不能重合；请先移动枢轴。');
        }

        const root = document.createElement('div');
        root.style.cssText = 'padding:8px;max-height:65vh;overflow:auto';
        function add(tag, text, parent = root) {
            const node = document.createElement(tag);
            if (text != null) node.textContent = text;
            parent.append(node);
            return node;
        }
        function control(label, value, type = 'text') {
            const row = add('label');
            row.style.cssText = 'display:grid;grid-template-columns: minmax(0,1fr) minmax(0,1.3fr);align-items:center;gap:8px;margin:6px 0';
            add('span', label, row);
            const input = add('input', null, row);
            input.style.cssText = 'width:100%;min-width:0';
            input.type = type;
            if (type === 'number') input.step = 'any';
            input.value = value;
            return input;
        }
        const name = control('骨骼名称', model.name);
        const typeRow = add('label', '物理骨类型 ');
        const type = add('select', null, typeRow);
        for (const [value, label] of [['ysmPhys', '普通物理骨'], ['ysmTail', '尾骨']]) add('option', label, type).value = value;
        type.value = model.kind || kind;
        const tailDefaults = {r:'10', g:'0', gw:'3', s:'16', a:'50', air:'0.5'};
        const inheritedParameters = () => scriptName(bone.parent)?.startsWith('ysmTail') === (type.value === 'ysmTail') ? parentValues : {};
        const defaults = key => type.value === 'ysmTail' && Object.hasOwn(tailDefaults, key) ? tailDefaults[key] : fields[key][1];
        const slider = control('拖动角度（相对动画轴）', '', 'range');
        slider.min = 0; slider.max = 180; slider.step = 0.1;
        add('p', '留空即继承直系物理父组；没有物理父组时使用 Reimagined YSM 默认值。');
        const inputs = {};
        for (const [key, [label, fallback]] of Object.entries(fields)) {
            const input = inputs[key] = control(label, model.values[key] ?? '');
            input.placeholder = parentValues[key] || fallback || '不限制';
            if (key === 'bc') {
                const select = add('select', null, input.parentNode);
                for (const [value, labelText] of [['', `继承 (${input.placeholder})`], ['true', '开启'], ['false', '关闭']]) {
                    add('option', labelText, select).value = value;
                }
                select.value = input.value;
                input.remove();
                inputs[key] = select;
            }
        }
        for (const [key, other] of [['g', 'gw'], ['gw', 'g']]) {
            inputs[key].addEventListener('input', () => {
                if (inputs[key].value.trim()) inputs[other].value = '';
            });
        }
        add('p', '重力阱朝当前模型/动画目标位置施加加速度：正数吸引、负数排斥、0 关闭。与重力互斥，填写任一项会清空另一项；留空继承同类型父组，否则使用该类型默认值。普通骨默认向下重力，尾骨默认重力阱 3、阻尼 10、回弹 16、最大偏角 50°，水平移动时沿链波动。');
        const animations = [...new Set(Animation.all.map(animation => animation.name))];
        if (animations.length) {
            const list = add('datalist');
            list.id = 'openysm_physics_animation_names';
            for (const animation of animations) add('option', null, list).value = animation;
            for (const key of ['enable', 'disable']) inputs[key].setAttribute('list', list.id);
        }
        add('p', '示例：walk,run（任一动画）；v.ready=1,v.roaming.speed>=0.5（全部成立）。同一栏不可混用。');
        if (!branches.length && !settings.export_empty_groups.value) {
            add('p', '导出提醒：请启用「导出空组 (Export Empty Groups)」，否则末端不会写入模型。');
        }
        const preview = add('code');
        preview.style.cssText = 'display:block;overflow-wrap:anywhere';
        const status = add('p');
        status.setAttribute('role', 'status');
        const proposedName = () => encode(name.value, Object.fromEntries(Object.entries(inputs).map(([key, input]) => [key, input.value.trim()])), type.value);
        let span, radius, note;
        const effective = key => inputs[key].value.trim() || inheritedParameters()[key] || defaults(key);
        function refresh() {
            const parent = inheritedParameters();
            for (const key of Object.keys(inputs)) inputs[key].placeholder = parent[key] || defaults(key) || '不限制';
            const gravity = inputs.g.value.trim(), well = inputs.gw.value.trim();
            const inheritedWell = parent.gw || (!parent.g && defaults('gw'));
            inputs.g.placeholder = well || (!gravity && inheritedWell) ? '已由重力阱替代' : parent.g || defaults('g');
            inputs.gw.placeholder = gravity || parent.g ? '未启用（由重力替代）' : inheritedWell || '未启用';
            span = Number(effective('a'));
            radius = Number(effective('c'));
            slider.value = span;
            note = `${span}°  r ${effective('r')}  air ${effective('air')}`;
            try { preview.textContent = proposedName(); status.textContent = ''; }
            catch (error) { preview.textContent = ''; status.textContent = error.message; }
        }
        root.addEventListener('input', () => { refresh(); commit(); });
        type.addEventListener('change', () => { refresh(); commit(); });
        slider.addEventListener('input', () => { inputs.a.value = slider.value; });
        refresh();

        function commit() {
            try {
                if (Project !== project || !Group.all.includes(bone) || scriptName(bone) !== oldName || bone.locked) throw new Error('目标骨骼已改变，请重新选择骨骼。');
                const newName = create ? uniqueName(proposedName(), bone) : proposedName();
                const taken = (label, except) => Group.all.some(other => other !== except && other.name.toLowerCase() === label.toLowerCase());
                if (taken(newName, bone)) throw new Error('生成的骨骼名已存在。');
                if (!create && newName === scriptName(bone)) return;
                const pivot = endpoint || branches.length ? null : movedEnd
                    ? (distance(origin, movedEnd.origin) > minLength ? movedEnd.origin.slice()
                        : origin.map((n, i) => 2 * n - bone.parent.origin[i])) : defaultEnd(origin);
                const endName = pivot && !movedEnd ? uniqueName(`ysmPhysEnd@${name.value.trim()}_end`) : null;
                const groups = [bone, movedEnd].filter(Boolean);
                Undo.initEdit({outliner: true, groups: groups.slice()});
                setScriptName(bone, newName);
                if (movedEnd) {
                    movedEnd.origin.splice(0, 3, ...pivot);
                    movedEnd.addTo(bone);
                    Canvas.updateAllBones([movedEnd]);
                }
                else if (pivot) groups.push(scriptify(new Group({name: endName, origin: pivot}).addTo(bone).init()));
                bone.isOpen = true;
                Undo.finishEdit('Edit Reimagined YSM physics group', {outliner: true, groups});
                oldName = scriptName(bone);
                inspectorKey = create ? undefined : selectionKey(bone);
            } catch (error) {
                if (create) throw error;
                status.textContent = error.message;
            }
        }
        if (create) { commit(); return; }
        add('p', '有效输入立即生效；无效输入保留上次有效值。可使用撤销恢复。');
        root.append(cancelPhysicsButton(bone, status));
        const candidates = children.filter(child => child.export && !child.locked && !scriptName(child).startsWith('ysmPhys'));
        if (candidates.length) {
            add('p', '延长物理链：接入已有直属子骨骼。');
            for (const child of candidates) {
                const extend = add('button', `接入 ${scriptName(child)}`);
                extend.type = 'button';
                extend.addEventListener('click', () => {
                    try {
                        if (Project !== project || Group.first_selected !== bone || child.parent !== bone) return;
                            edit(child, true, type.value);
                        child.select(new MouseEvent('click'), true);
                    } catch (error) { status.textContent = error.message; }
                });
            }
        }
        panel.node.replaceChildren(root);
        draw = () => {
            const object = bone.scene_object;
            if (!object || !Number.isFinite(span) || span < 0 || span > 180) return [];
            const anchor = object.getWorldPosition(new THREE.Vector3());
            const outputs = branches.map(child => child.scene_object?.getWorldPosition(new THREE.Vector3())).filter(Boolean);
            if (endpoint?.scene_object) outputs.push(endpoint.scene_object.getWorldPosition(new THREE.Vector3()));
            return outputs.map(target => ({anchor, target, span, radius, note}));
        };
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
        scriptify(group);
        const cube = colliderCube(group);
        group.isOpen = true;
        cube.select(new MouseEvent('click'), true);
        Undo.finishEdit('Add Reimagined YSM collision box', {outliner:true, elements:[cube], selection:true});
    }

    function createCollider() {
        const parent = Group.first_selected;
        for (let node = parent; node instanceof Group; node = node.parent) {
            if (node.locked || !node.export || joint.test(scriptName(node)) || marker.test(scriptName(node))) {
                Blockbench.showQuickMessage('请选中可导出的普通骨骼，或取消选择后在模型根部创建。');
                return;
            }
        }
        const origin = parent?.origin.slice() ?? [0, 0, 0];
        Undo.initEdit({outliner:true, groups:[], elements:[], selection:true});
        const group = new Group({name:uniqueName(`ysmPhysC@${parent ? scriptName(parent).replace(/@/g, '_') + '_' : ''}collider`), origin})
            .addTo(parent ?? 'root').init();
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
        title.textContent = `${scriptName(group)} · 无 UV 方块也可从这里选中，再用原生移动、缩放、旋转工具编辑。`;
        root.append(title);
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

    const collider = /^ysmPhysC@[^@]+$/;
    let showBones = savedSwitch('showBones', true), showVolumes = savedSwitch('showVolumes', true);
    let pickColliders = savedSwitch('pickColliders', false), pickPhysicalGroups = savedSwitch('pickPhysicalGroups', false), overlay;
    const usable = group => group instanceof Group && group.export;
    function physicsTopology(groups) {
        const parameters = new Map();
        function resolve(bone) {
            if (!usable(bone) || !joint.test(scriptName(bone))) return null;
            if (!parameters.has(bone)) {
                try { parameters.set(bone, inheritValues(resolve(bone.parent), read(scriptName(bone)).values)); }
                catch { parameters.set(bone, null); }
            }
            return parameters.get(bone);
        }
        const segments = [];
        const distinct = (a, b) => Math.hypot(...a.origin.map((x, i) => x - b.origin[i])) > 16e-6;
        function chain(bone) {
            const children = bone.children.filter(child => resolve(child));
            const ends = bone.children.filter(child => usable(child) && end.test(scriptName(child)));
            const valid = children.filter(child => distinct(child, bone)).map(child => [child, chain(child)]).filter(([, result]) => result);
            if (valid.length ? ends.length : (ends.length !== 1 || ends[0].children.length || !distinct(ends[0], bone))) return null;
            const radius = Number(resolve(bone).c ?? fields.c[1]);
            return valid.length ? valid.flatMap(([child, result]) => [{bone, target:child, radius}, ...result])
                : [{bone, target:ends[0], radius}];
        }
        for (const bone of groups) if (resolve(bone) && !resolve(bone.parent)) segments.push(...(chain(bone) ?? []));
        const cubes = [];
        if (segments.length) for (const group of groups) if (usable(group) && collider.test(scriptName(group))) {
            let ignored = false;
            for (let ancestor = group.parent; ancestor instanceof Group; ancestor = ancestor.parent) {
                if (resolve(ancestor) || end.test(scriptName(ancestor))) { ignored = true; break; }
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
        const groups = Group.all.filter(group => scriptName(group).startsWith('ysmPhys') || scriptName(group).startsWith('ysmTail'));
        const key = JSON.stringify([Project.uuid, showBones, showVolumes, groups.map(group => [group.uuid, scriptName(group),
            group.export, group.origin, collider.test(scriptName(group)) ? colliderParentKey(group) : group.parent?.uuid, collider.test(scriptName(group)) || end.test(scriptName(group))
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
        if (!outputs.length) return;
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
            lines(axis, sectorLines(span).map(n => n * length), 0x58aaff);
            if (showVolumes && Number.isFinite(radius) && radius > 0) {
                const ring = [];
                const point = i => [radius * Math.cos(i * Math.PI / 12), length / 2, radius * Math.sin(i * Math.PI / 12)];
                for (let i = 0; i < 24; i++) ring.push(...point(i), ...point(i + 1));
                lines(axis, ring, 0x53e3aa);
            }
            if (index) return;
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
                    if (marker.test(scriptName(group))) break;
                    if (joint.test(scriptName(group))) { target = group; break; }
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
        editor.style.cssText = 'overflow:auto;min-height:0'; editor.append(create, selectionTitle, node);
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
        const available = () => supported() && (Modes.edit || Modes.animate);
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

    function selectionKey(bone) {
        return JSON.stringify(bone ? [Project.uuid, bone.uuid, scriptName(bone), bone.origin, bone.locked, bone.export,
            scriptName(bone.parent), Cube.selected?.[0]?.uuid, bone.children.map(child => [child.uuid, scriptName(child), child.export, child.locked,
                end.test(scriptName(child)) ? [child.origin, child.locked, child.children?.length] : null])] : []);
    }

    const showPanel = () => panel.show();
    function tick() {
        frame = requestAnimationFrame(tick);
        updateOverlay(supported() && (Modes.edit || Modes.animate));
        const selected = supported() && Modes.edit ? Group.first_selected : null;
        const cubeParent = supported() && Modes.edit ? Cube.selected?.[0]?.parent : null;
        const bone = collider.test(scriptName(cubeParent)) ? cubeParent : selected;
        const active = panel.sync(supported() && (Modes.edit || Modes.animate)) && Modes.edit;
        if (!active) {
            clearGizmos();
            drawnKey = undefined;
            return;
        }
        const key = selectionKey(bone);
        if (key !== inspectorKey) {
            inspectorKey = key;
            draw = undefined;
            try {
                if (!bone || bone.locked || !bone.export) throw new Error('选择一个未锁定、可导出的骨骼组。');
                if (/^ysm(?:Cosmic|Outline)/.test(scriptName(bone))) editEffect(bone, readEffect(scriptName(bone)).kind);
                else if (joint.test(scriptName(bone))) edit(bone);
                else if (collider.test(scriptName(bone))) inspectCollider(bone);
                else if (end.test(scriptName(bone))) throw new Error('这是物理末端标记，请选择父物理骨骼。');
                else {
                    const button = document.createElement('button');
                    button.type = 'button';
                    button.textContent = '创建物理组';
                    const status = document.createElement('p');
                    status.setAttribute('role', 'status');
                    button.addEventListener('click', () => {
                        try {
                            if (Group.first_selected !== bone) return;
                            edit(bone, true);
                        } catch (error) { status.textContent = error.message; }
                    });
                    const colliderButton = document.createElement('button');
                    colliderButton.type = 'button';
                    colliderButton.textContent = '创建碰撞体积';
                    colliderButton.addEventListener('click', () => {
                        if (Group.first_selected === bone) createCollider();
                    });
                    const tailButton = document.createElement('button');
                    tailButton.type = 'button';tailButton.textContent = '创建尾骨';
                    tailButton.onclick = () => {
                        try { if (Group.first_selected === bone) edit(bone, true, 'ysmTail'); }
                        catch (error) { status.textContent = error.message; }
                    };
                    const itemButton = document.createElement('button');
                    itemButton.type = 'button';
                    const isItem = /^ysmItem@[^@]+$/.test(scriptName(bone));
                    itemButton.textContent = isItem ? '取消单面厚度' : '启用单面厚度';
                    itemButton.onclick = () => {
                        try {
                            if (Group.first_selected !== bone || bone.locked) return;
                            const cubes = bone.children.filter(child => child instanceof Cube && child.export);
                            if (!isItem && (cubes.length !== 1 || Object.values(cubes[0].faces).filter(face => face.texture !== null).length !== 1)) {
                                throw new Error('单面厚度需要组内恰好一个可导出方块，且只有一个面分配了纹理。');
                            }
                            Undo.initEdit({outliner:true, groups:[bone]});
                            setScriptName(bone, uniqueName(isItem ? scriptName(bone).slice(8) : `ysmItem@${scriptName(bone)}`, bone));
                            Undo.finishEdit('Toggle YSM item thickness', {outliner:true, groups:[bone]});
                            inspectorKey = undefined;
                        } catch (error) { status.textContent = error.message; }
                    };
                    const help = document.createElement('p');
                    help.textContent = '尾骨默认悬浮回弹，水平移动时波动；单面厚度由游戏按贴图轮廓生成，厚度为 1 模型像素，Blockbench 中保持原始方块。';
                    const effects = document.createElement('div');
                    effects.className = 'rysm-action-grid';
                    for (const [kind, label] of [['ysmCosmic', '创建星空着色器组'], ['ysmOutline', '创建描边组']]) {
                        const action = document.createElement('button'); action.type = 'button'; action.textContent = label;
                        action.onclick = () => { if (Group.first_selected === bone) editEffect(bone, kind, true); };
                        effects.append(action);
                    }
                    const physics = document.createElement('div'); physics.className = 'rysm-action-grid';
                    physics.append(button, tailButton, colliderButton, itemButton);
                    const heading = document.createElement('h3'); heading.textContent = scriptName(bone);
                    const note = document.createElement('p'); note.textContent = '将当前组设为以下类型；已有特殊组请用父子组嵌套组合。';
                    panel.node.replaceChildren(heading, note, physics, effects, help, status);
                }
            } catch (error) {
                const message = document.createElement('p');
                message.textContent = error.message;
                panel.node.replaceChildren(message);
                if (bone && !bone.locked && bone.export && joint.test(scriptName(bone))) panel.node.append(cancelPhysicsButton(bone, message));
            }
        }
        const outputs = draw?.() ?? [];
        const geometryKey = JSON.stringify([showBones, showVolumes, outputs]);
        if (geometryKey !== drawnKey) {
            drawnKey = geometryKey;
            renderGizmos(outputs);
        }
    }

    // New projects persist ordinary names plus model-local script properties.
    function scriptName(group) {
        if (!group) return undefined;
        const p=group.rysm_properties || {}, name=group.name;
        if (p.physics) { const v={...p.physics}; const kind=v.kind === 'tail' ? 'ysmTail' : 'ysmPhys'; delete v.kind;
            for(const key of ['enable','disable'])if(v[key])v[key]=String(v[key]).replace(/^\(|\)$/g,'');
            return encode(name,v,kind);
        }
        if(p.end)return `ysmPhysEnd@${name}`;
        if(p.collider)return `ysmPhysC@${name}`;
        if(p.item)return `ysmItem@${name}`;
        for(const [key,prefix] of [['cosmic','ysmCosmic'],['outline','ysmOutline']])if(p[key]) {
            const values={...p[key]};delete values.effect;return encodeEffect(name,values,prefix);
        }
        return name;
    }
    function setScriptName(group, value) {
        const p={...(group.rysm_properties || {})}; let name=value;
        if(joint.test(value)) {
            for(const key of ['physics','end','collider','item'])delete p[key];
            const decoded=read(value);name=decoded.name;p.physics={...decoded.values};
            if(decoded.kind==='ysmTail')p.physics.kind='tail';
            for(const key of ['enable','disable'])if(p.physics[key])p.physics[key]=`(${p.physics[key]})`;
        }else if(effect.test(value)) {
            const decoded=readEffect(value);name=decoded.name;
            const key=decoded.kind==='ysmCosmic'?'cosmic':'outline';
            p[key]={...p[key],...decoded.values};
        }else {
            const markerEntry=[['ysmPhysEnd@','end'],['ysmPhysC@','collider'],['ysmItem@','item']].find(([prefix])=>value.startsWith(prefix));
            if(markerEntry){for(const key of ['physics','end','collider','item'])delete p[key];name=value.slice(markerEntry[0].length);p[markerEntry[1]]=true;}
            else {
                const old=scriptName(group);
                if(joint.test(old)||marker.test(old))for(const key of ['physics','end','collider','item'])delete p[key];
                else if(effect.test(old))delete p[old.startsWith('ysmCosmic')?'cosmic':'outline'];
            }
        }
        if(Group.all.some(other=>other!==group && other.name===name))throw new Error(`普通组名已存在：${name}`);
        group.name=name;group.rysm_properties=p;group.rysm_script_owned=true;
    }
    function scriptify(group) {setScriptName(group,group.name);return group;}
    function scriptSource() {
        const groups=Group.all.filter(g=>g.export && (g.rysm_script_owned || Object.keys(g.rysm_properties || {}).length));
        if(new Set(groups.map(g=>g.name)).size!==groups.length)throw new Error('脚本组名称不能重复');
        return '// Generated by the RYSM Blockbench editor.\n'+groups.map(g=>`Rysm.group(${JSON.stringify(g.name)}, ${JSON.stringify(g.rysm_properties || {})});`).join('\n')+'\n';
    }
    function writeScripts() {
        if(typeof require!=='function') {
            Blockbench.export({type:'RYSM JavaScript',extensions:['js'],name:'blockbench',content:scriptSource()});return;
        }
        const fs=require('fs'),path=require('path');let root=path.dirname(Project.save_path || Project.export_path || '');
        for(let i=0;i<5 && root && !fs.existsSync(path.join(root,'ysm.json'));i++)root=path.dirname(root);
        if(!root || !fs.existsSync(path.join(root,'ysm.json'))) {Blockbench.showQuickMessage('请将项目保存到含 ysm.json 的模型目录后重试');return;}
        const folder=path.join(root,'rysm'),manifestPath=path.join(folder,'manifest.json');
        let manifest=fs.existsSync(manifestPath)?JSON.parse(fs.readFileSync(manifestPath,'utf8')):{version:1,scripts:[]};
        if(manifest.version!==1 || !Array.isArray(manifest.scripts))throw new Error('不支持的 RYSM 清单');
        fs.mkdirSync(path.join(folder,'scripts'),{recursive:true});
        const writes=new Map();
        // Import only our literal generated registrations. Unhandled migration entries stay active.
        const migrated=path.join(folder,'scripts','migrated.js');
        if(manifest.scripts.includes('scripts/migrated.js') && fs.existsSync(migrated)) {
            const content=fs.readFileSync(migrated,'utf8'), entries=generatedGroups(content);
            const owned=new Set(Group.all.filter(g=>g.export && (g.rysm_script_owned || Object.keys(g.rysm_properties || {}).length)).map(g=>g.name));
            const remaining=entries.filter(([name])=>!owned.has(name));
            if(remaining.length!==entries.length) {
                fs.copyFileSync(migrated,migrated+'.'+Date.now()+'.bak');
                writes.set(migrated,'// Automatically migrated from group-name properties.\n'+remaining.map(([name,p])=>`Rysm.group(${JSON.stringify(name)}, ${JSON.stringify(p)});`).join('\n')+'\n');
                if(!remaining.length)manifest.scripts=manifest.scripts.filter(s=>s!=='scripts/migrated.js');
            }
        }
        writes.set(path.join(folder,'scripts','blockbench.js'),scriptSource());
        if(!manifest.scripts.includes('scripts/blockbench.js'))manifest.scripts.push('scripts/blockbench.js');
        writes.set(manifestPath,JSON.stringify(manifest,null,2));writeScriptTransaction(writes);
    }
    function editScriptProperties() {
        const group=Group.first_selected;if(!group)throw new Error('请选择一个组');
        const p=group.rysm_properties || {},effects={'':'默认星空'},root=modelRoot();
        if(root){const fs=require('fs'),path=require('path'),folder=path.join(root,'rysm','cosmic');if(fs.existsSync(folder))for(const entry of fs.readdirSync(folder,{withFileTypes:true}))if(entry.isDirectory())effects[entry.name]=entry.name;}
        if(p.cosmic?.effect)effects[p.cosmic.effect]=p.cosmic.effect;
        new Dialog({id:'rysm_js_group',title:'RYSM 脚本组属性',width:700,form:{
            physics:{label:'物理类型',type:'select',options:{none:'无',normal:'物理骨',tail:'尾骨',end:'末端',collider:'碰撞体'},value:p.end?'end':p.collider?'collider':p.physics?p.physics.kind==='tail'?'tail':'normal':'none'},
            physics_values:{label:'物理参数 JSON',type:'textarea',value:JSON.stringify(p.physics || {},null,2)},
            facingCamera:{label:'面向镜头',type:'checkbox',value:!!p.facingCamera},glow:{label:'发光',type:'checkbox',value:!!p.glow},item:{label:'单面厚度',type:'checkbox',value:!!p.item},
            cosmic:{label:'星空',type:'select',options:{inherit:'继承',enable:'启用',disable:'禁用'},value:Object.hasOwn(p,'cosmic')?p.cosmic===false?'disable':'enable':'inherit'},effect:{label:'星空效果（自定义效果需在 JS 中 Rysm.cosmic 声明）',type:'select',options:effects,value:p.cosmic?.effect || ''},
            cosmic_values:{label:'星空颜色参数 JSON',type:'textarea',value:JSON.stringify(Object.fromEntries(Object.entries(p.cosmic || {}).filter(([key])=>key!=='effect')),null,2)},
            outline:{label:'描边',type:'select',options:{inherit:'继承',enable:'启用',disable:'禁用'},value:Object.hasOwn(p,'outline')?p.outline===false?'disable':'enable':'inherit'},outline_values:{label:'描边参数 JSON',type:'textarea',value:JSON.stringify(p.outline || {},null,2)},
            frameAnimation:{label:'帧动画',type:'checkbox',value:!!p.frameAnimation},frame_values:{label:'帧动画参数 JSON',type:'textarea',value:JSON.stringify(p.frameAnimation || {count:2,tick:6},null,2)}},
            onConfirm(form) {
                const object=text=>{const value=JSON.parse(text);if(!value||Array.isArray(value)||typeof value!=='object')throw new Error('参数必须为 JSON 对象');return value;},properties={};
                if(['normal','tail'].includes(form.physics)){properties.physics=object(form.physics_values);delete properties.physics.kind;if(form.physics==='tail')properties.physics.kind='tail';}
                if(['end','collider'].includes(form.physics))properties[form.physics]=true;
                for(const key of ['facingCamera','glow','item'])if(form[key])properties[key]=true;
                if(form.cosmic==='enable'){properties.cosmic=object(form.cosmic_values);if(form.effect)properties.cosmic.effect=form.effect;}else if(form.cosmic==='disable')properties.cosmic=false;
                if(form.outline==='enable')properties.outline=object(form.outline_values);else if(form.outline==='disable')properties.outline=false;
                if(form.frameAnimation)properties.frameAnimation=object(form.frame_values);
                Undo.initEdit({outliner:true,groups:[group]});group.rysm_properties=properties;group.rysm_script_owned=true;Undo.finishEdit('Edit RYSM script properties',{outliner:true,groups:[group]});this.hide();
            }
        }).show();
    }
    function importLegacyGroups() {
        const groups=Group.all.filter(g=>joint.test(g.name)||marker.test(g.name)||effect.test(g.name));
        Undo.initEdit({outliner:true,groups});
        for(const group of groups)scriptify(group);
        Undo.finishEdit('Convert legacy RYSM group names',{outliner:true,groups});
        writeScripts();
    }
    function saveScripts() {
        if(!supported())return;
        try{writeScripts();}catch(error){Blockbench.showQuickMessage(`RYSM 脚本保存失败：${error.message}`);}
    }
    function atomicText(file,content) {
        const fs=require('fs'),temp=file+'.'+Date.now()+'.tmp';
        try{fs.writeFileSync(temp,content,'utf8');fs.renameSync(temp,file);}finally{if(fs.existsSync(temp))fs.unlinkSync(temp);}
    }
    function writeScriptTransaction(writes) {
        const fs=require('fs'),before=new Map();
        for(const file of writes.keys()){if(fs.existsSync(file)&&!fs.lstatSync(file).isFile())throw new Error('脚本目标必须为普通文件');before.set(file,fs.existsSync(file)?fs.readFileSync(file,'utf8'):null);}
        try{for(const [file,text] of writes)atomicText(file,text);}
        catch(error){for(const [file,text] of before)try{if(text===null){if(fs.existsSync(file))fs.unlinkSync(file);}else atomicText(file,text);}catch(rollback){error.message+='；恢复失败: '+file+' '+rollback.message;}throw error;}
    }
    function modelRoot() {
        if(typeof require!=='function')return null;
        const fs=require('fs'),path=require('path');let root=path.dirname(Project.save_path || Project.export_path || '');
        for(let i=0;i<8;i++){if(fs.existsSync(path.join(root,'ysm.json')))return root;const parent=path.dirname(root);if(parent===root)break;root=parent;}
        return null;
    }
    function scriptPath(name) {
        if(!validScriptFile(name) || name==='scripts/blockbench.js' || name==='scripts/migrated.js')
            throw new Error('手写脚本须为 scripts/名称.js，不能覆盖 blockbench.js 或 migrated.js');
        return name;
    }
    function validScriptFile(name){return typeof name==='string'&&name.startsWith('scripts/')&&name.endsWith('.js')&&!/[\\:\x00-\x1f]/.test(name)&&name.split('/').every(part=>part&&part!=='.'&&part!=='..');}
    function generatedGroups(source) {
        if(!/^\/\/ (?:Automatically migrated|Migrated binary model|Generated by the RYSM)/.test(source))throw new Error('无法安全读取迁移脚本，请先手动合并其中的属性');
        const result=[], pattern=/Rysm\.group\(("(?:\\.|[^"\\])*"),\s*(\{[^\n]*\})\);/g;
        let match;while((match=pattern.exec(source)))result.push([JSON.parse(match[1]),JSON.parse(match[2])]);
        if(source.replace(pattern,'').replace(/\/\/[^\n]*/g,'').trim())throw new Error('迁移脚本包含手写逻辑，请先手动合并');
        return result;
    }
    function importScriptGroups() {
        const root=modelRoot();if(!root)throw new Error('请先保存到含 ysm.json 的模型目录');
        const fs=require('fs'),path=require('path'), manifestFile=path.join(root,'rysm','manifest.json');
        if(!fs.existsSync(manifestFile))return;
        const manifest=JSON.parse(fs.readFileSync(manifestFile,'utf8')), entries=[];
        for(const name of ['scripts/migrated.js','scripts/blockbench.js'])if(manifest.scripts.includes(name))entries.push(...generatedGroups(fs.readFileSync(path.join(root,'rysm',name),'utf8')));
        const groups=Group.all.filter(g=>entries.some(([name])=>name===g.name));
        Undo.initEdit({outliner:true,groups});
        for(const group of groups){const properties=entries.find(([name])=>name===group.name)[1];group.rysm_properties=JSON.parse(JSON.stringify(properties));group.rysm_script_owned=true;}
        Undo.finishEdit('Import RYSM script properties',{outliner:true,groups});
        Blockbench.showQuickMessage(`已导入 ${groups.length} 个组；保存时仅合并这些组的迁移声明`);
    }
    function scriptFiles() {
        const files={},root=modelRoot();
        if(root) {
            const fs=require('fs'),path=require('path'),file=path.join(root,'rysm','manifest.json');
            if(fs.existsSync(file))for(const name of JSON.parse(fs.readFileSync(file,'utf8')).scripts || []) {
                if(!validScriptFile(name))throw new Error('清单中的脚本路径无效');
                const source=path.join(root,'rysm',name);if(fs.statSync(source).size>1048576)throw new Error('脚本超过 1 MiB');
                files[name]=fs.readFileSync(source,'utf8');
            }
        }
        for(const [name,source] of Object.entries(Project.rysm_scripts || {}))if(!Object.hasOwn(files,name))files[scriptPath(name)]=source;
        if(files['scripts/migrated.js']){
            const owned=new Set(Group.all.filter(g=>g.rysm_script_owned||Object.keys(g.rysm_properties || {}).length).map(g=>g.name));
            files['scripts/migrated.js']='// Migration registrations not edited in this project.\n'+generatedGroups(files['scripts/migrated.js']).filter(([name])=>!owned.has(name)).map(([name,p])=>`Rysm.group(${JSON.stringify(name)}, ${JSON.stringify(p)});`).join('\n');
        }
        files['scripts/blockbench.js']=scriptSource();return files;
    }
    function saveUserScript(name,source) {
        scriptPath(name);if(source.length>1048576)throw new Error('脚本超过 1 MiB');
        const root=modelRoot();
        if(root) {
            const fs=require('fs'),path=require('path'),folder=path.join(root,'rysm'),file=path.join(folder,'manifest.json');
            const manifest=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{version:1,scripts:[]};
            if(manifest.version!==1||!Array.isArray(manifest.scripts))throw new Error('不支持的清单');
            const target=path.join(folder,name);fs.mkdirSync(path.dirname(target),{recursive:true});
            if(fs.existsSync(target))fs.copyFileSync(target,target+'.'+Date.now()+'.bak');
            if(!manifest.scripts.includes(name))manifest.scripts.push(name);writeScriptTransaction(new Map([[target,source],[file,JSON.stringify(manifest,null,2)]]));
        }
        Project.rysm_scripts={...(Project.rysm_scripts || {}),[name]:source};
        if(!root)Blockbench.export({type:'RYSM JavaScript',extensions:['js'],name:name.split('/').pop().slice(0,-3),content:source});
    }
    // Runs only inside an isolated worker. No model code is evaluated in Blockbench's Electron context.
    function previewWorker() {
        'use strict';
        const post=self.postMessage.bind(self),loadScript=self.importScripts.bind(self);
        for(const key of ['fetch','XMLHttpRequest','WebSocket','WebTransport','EventSource','importScripts','Worker','SharedWorker','BroadcastChannel','indexedDB','caches','navigator','postMessage'])
            Object.defineProperty(self,key,{value:undefined,writable:false,configurable:false});
        const groups=Object.create(null),cosmic=Object.create(null),molang=Object.create(null),updates=[],poses=[],state={},evaluating=new Set(),warnings=new Set();
        let registering=true,context,transforms,currentFile='';
        function errorText(error){return String(error.stack || error.message || error);}
        function finite(value){const n=Number(value);if(!Number.isFinite(n))throw new Error('返回值必须为有限数值或布尔值');return n;}
        function warn(name,e){if(!warnings.has(name)){warnings.add(name);post({type:'warning',message:name+': '+errorText(e)});}}
        function evaluate(name,args=[]) {
            const entry=molang[name];if(!entry)return 0;
            if(entry.sync && args.length)return entry.fallback;
            if(entry.sync && !context.entity.local)return finite(context.remote[name] ?? entry.fallback);
            if(evaluating.has(name))return entry.fallback;
            if(entry.sync && Object.hasOwn(context.cache,name))return context.cache[name];
            evaluating.add(name);
            try{const value=finite(entry.fn(context,...args));if(entry.sync)context.cache[name]=value;return value;}
            catch(e){warn(name,e);return entry.fallback;}finally{evaluating.delete(name);}
        }
        function register(target,name,value){if(!registering || !name || Object.hasOwn(target,name))throw new Error('注册已关闭或名称重复: '+name);Object.defineProperty(target,name,{value,enumerable:true});}
        const Rysm=Object.freeze({
            group:(name,value)=>register(groups,name,value),cosmic:(name,value)=>register(cosmic,name,value),logKeyBindings:()=>{},
            molang:(name,fn,options={})=>{if(!/^[a-z_][a-z0-9_]*$/.test(name)||typeof fn!=='function'||options.sync&&fn.length>1)throw new Error('Molang 声明无效');register(molang,name,{fn,fallback:finite(options.fallback ?? 0),sync:!!options.sync});},
            update:fn=>{if(!registering||typeof fn!=='function')throw new Error('update 声明无效');updates.push(fn);},
            pose:fn=>{if(!registering||typeof fn!=='function')throw new Error('pose 声明无效');poses.push(fn);}
        });
        Object.defineProperty(self,'Rysm',{value:Rysm,writable:false,configurable:false});
        function freeze(value){if(value&&typeof value==='object'){for(const child of Object.values(value))freeze(child);Object.freeze(value);}return value;}
        self.onmessage=event=>{
            try {
                const message=event.data;
                if(message.type==='load') {
                    for(const [name,source] of Object.entries(message.files)){
                        currentFile=name;
                        const url=URL.createObjectURL(new Blob([source+'\n//# sourceURL=rysm/'+name],{type:'text/javascript'}));try{loadScript(url);}finally{URL.revokeObjectURL(url);}
                    }
                    registering=false;post({type:'ready',groups,cosmic});return;
                }
                const mock=message.mock;
                context={entity:freeze(mock.entity),camera:freeze(mock.camera),time:freeze({ticks:message.ticks,seconds:message.ticks/20}),state,remote:mock.remote || {},cache:Object.create(null),
                    input:freeze({key:id=>mock.entity.local?freeze(message.keys[id] || {down:false,pressed:false,released:false}):freeze({down:false,pressed:false,released:false}),
                        binding:id=>mock.entity.local?freeze(message.bindings[id] || {down:false,pressed:false,released:false}):freeze({down:false,pressed:false,released:false})}),
                    molang:text=>{
                        const match=/^rysmjs\.([a-z_][a-z0-9_]*)(?:\(([^()]*)\))?$/.exec(text);
                        if(match)return evaluate(match[1],match[2]?.trim()?match[2].split(',').map(finite):[]);
                        if(Object.hasOwn(mock.molang || {},text))return finite(mock.molang[text]);
                        throw new Error('请在模拟 Molang 中提供 '+text);
                    },group:()=>{throw new Error('组变换仅可用于 Rysm.pose');}};
                for(const callback of updates)try{callback(context);}catch(e){warn('update',e);}
                const values=Object.create(null);for(const name of Object.keys(molang))values[name]=evaluate(name);
                transforms=[];context.group=name=>{
                    const group={};for(const op of ['move','rotate','scale'])group[op]=(...args)=>{if(args.length!==3)throw new Error('变换需要 x y z');transforms.push({name,op,values:args.map(finite)});return group;};return Object.freeze(group);
                };
                for(const callback of poses)try{callback(context);}catch(e){warn('pose',e);}
                post({type:'frame',values,transforms,ticks:message.ticks});
            }catch(e){post({type:'error',message:currentFile+': '+errorText(e)});}
        };
    }
    let scriptPreview,scriptEditor;
    function stopScriptPreview() {
        if(!scriptPreview)return;
        const preview=scriptPreview;scriptPreview=null;clearInterval(preview.timer);clearTimeout(preview.watchdog);
        window.removeEventListener('message',preview.listener);preview.frame.remove();
        for(const [mesh,saved] of preview.meshes){mesh.position.copy(saved.position);mesh.quaternion.copy(saved.quaternion);mesh.scale.copy(saved.scale);mesh.material=saved.material;}
        for(const material of preview.materials)material.dispose();for(const atlas of preview.atlases.values())atlas.texture.dispose();
    }
    function inputEdges(down,previous) {
        const out={};for(const name of new Set([...down,...previous]))out[name]={down:down.has(name),pressed:down.has(name)&&!previous.has(name),released:!down.has(name)&&previous.has(name)};return out;
    }
    function startScriptPreview(files,readMock,report) {
        stopScriptPreview();
        const preview={frame:document.createElement('iframe'),meshes:new Map(),materials:[],atlases:new Map(),warnings:[],previousKeys:new Set(),previousBindings:new Set(),start:performance.now(),busy:true,project:Project};
        scriptPreview=preview;
        for(const element of [...Group.all,...Cube.all])if(element.mesh&&!preview.meshes.has(element.mesh))preview.meshes.set(element.mesh,{position:element.mesh.position.clone(),quaternion:element.mesh.quaternion.clone(),scale:element.mesh.scale.clone(),material:element.mesh.material});
        const nonce=crypto.randomUUID();
        const workerCode='('+previewWorker.toString()+')();';
        const bootstrap=`try {const worker=new Worker(URL.createObjectURL(new Blob([${JSON.stringify(workerCode)}],{type:'text/javascript'})));worker.onmessage=e=>parent.postMessage({nonce:${JSON.stringify(nonce)},data:e.data},'*');worker.onerror=e=>parent.postMessage({nonce:${JSON.stringify(nonce)},data:{type:'error',message:e.message}},'*');onmessage=e=>{if(e.source===parent)worker.postMessage(e.data)};parent.postMessage({nonce:${JSON.stringify(nonce)},data:{type:'boot'}},'*');}catch(e){parent.postMessage({nonce:${JSON.stringify(nonce)},data:{type:'error',message:e.stack || e.message}},'*');}`;
        preview.frame.setAttribute('sandbox','allow-scripts');preview.frame.style.display='none';
        preview.frame.srcdoc='<meta http-equiv="Content-Security-Policy" content="default-src &#39;none&#39;; script-src &#39;unsafe-inline&#39; &#39;unsafe-eval&#39; blob:; worker-src blob:; connect-src &#39;none&#39;"><script>'+bootstrap.replace(/<\/script/gi,'<\\/script')+'</script>';
        function timeout(){stopScriptPreview();report('预览超时，已终止脚本 Worker');}
        preview.watchdog=setTimeout(timeout,2500);
        preview.listener=event=>{
            if(scriptPreview!==preview||event.source!==preview.frame.contentWindow||event.data?.nonce!==nonce)return;
            const data=event.data.data;
            if(data.type==='boot'){preview.frame.contentWindow.postMessage({type:'load',files},'*');return;}
            if(data.type==='warning'){if(preview.warnings.length<32)preview.warnings.push(data.message);report(preview.warnings.join('\n'));return;}
            clearTimeout(preview.watchdog);
            if(data.type==='error'){stopScriptPreview();report(data.message);return;}
            if(data.type==='ready'){preview.groups=data.groups;preview.cosmic=data.cosmic;preview.busy=false;report('已启动；物理仅显示骨链/碰撞辅助，算法与效果使用模拟数据');return;}
            if(data.type==='frame'){preview.busy=false;try{applyScriptPreview(preview,data,readMock());report(preview.warnings.join('\n')+'\n'+JSON.stringify(data.values,null,2));}catch(e){stopScriptPreview();report(e.message);}}
        };
        window.addEventListener('message',preview.listener);document.body.append(preview.frame);
        preview.timer=setInterval(()=>{
            if(Project!==preview.project){stopScriptPreview();return;}
            if(preview.busy)return;
            try {
                const mock=readMock(),keys=new Set(mock.keys || []),bindings=new Set(mock.bindings || []);
                const message={type:'tick',ticks:(performance.now()-preview.start)/50,mock,keys:inputEdges(keys,preview.previousKeys),bindings:inputEdges(bindings,preview.previousBindings)};
                preview.previousKeys=keys;preview.previousBindings=bindings;preview.busy=true;
                preview.watchdog=setTimeout(timeout,250);preview.frame.contentWindow.postMessage(message,'*');
            }catch(e){stopScriptPreview();report(e.message);}
        },50);
    }
    function editScripts() {
        const files=scriptFiles(),editable=Object.keys(files).filter(name=>!['scripts/blockbench.js','scripts/migrated.js'].includes(name));
        const initial=editable[0] || 'scripts/main.js';
        const defaults={entity:{health:20,maxHealth:20,position:{x:0,y:0,z:0},velocity:{x:0,y:0,z:0},pose:'standing',yaw:0,pitch:0,local:true},camera:{yaw:0,pitch:0},molang:{'query.health':20,'v.tick':0},keys:[],bindings:[],remote:{}};
        const output=document.createElement('pre');output.style.cssText='white-space:pre-wrap;max-height:180px;overflow:auto';
        scriptEditor=new Dialog({id:'rysm_script_editor',title:'RYSM JS 编辑与预览',width:900,
            form:{existing:{label:'载入文件',type:'select',options:Object.fromEntries(editable.map(name=>[name,name])),value:initial},name:{label:'脚本路径',type:'text',value:initial},
                source:{label:'JavaScript（Rhino ES6 子集）',type:'textarea',value:files[initial] || 'Rysm.molang("example", ctx => ctx.entity.health / ctx.entity.maxHealth, {fallback:1});\n'},
                mock:{label:'预览模拟数据 JSON（keys/bindings 填按住的标识；remote 填远端同步输出）',type:'textarea',value:JSON.stringify(defaults,null,2)}},
            lines:['<p>仅在隔离 Worker 执行。编辑模拟 keys 可测试按下/松开；不消费 BB 快捷键。物理不模拟运动。</p>',output],
            buttons:['保存','运行预览','停止','关闭'],confirmIndex:-1,cancelIndex:3,
            onFormChange(form){if(form.existing&&form.existing!==this.loaded){this.loaded=form.existing;this.setFormValues({name:form.existing,source:files[form.existing] || ''});}},
            onButton(index){try {
                const form=this.getFormResult();
                if(index===0){saveUserScript(form.name,form.source);files[form.name]=form.source;output.textContent='已保存 '+form.name;return false;}
                if(index===1){scriptPath(form.name);const all={...files,[form.name]:form.source};startScriptPreview(all,()=>JSON.parse(this.getFormResult().mock),message=>{output.textContent=message;});return false;}
                stopScriptPreview();if(index===2){output.textContent='已停止并恢复视口';return false;}
            }catch(e){output.textContent=e.stack || e.message;return false;}},
            onCancel(){stopScriptPreview();}
        });scriptEditor.loaded=initial;scriptEditor.show();
    }
    // RYSM_PREVIEW_ASSETS: game shader and Avaritia sprites; provenance: assets/yes_steve_model/textures/cosmic/SOURCE.txt.
    const cosmicPreviewFragment = "\n\n#define M_PI 3.1415926535897932384626433832795\n\nuniform int cosmiccount;\nuniform float cosmicoutof;\n\nuniform sampler2D texture0;\nuniform sampler2D stars;\nuniform vec4 spaceTint; uniform vec4 astralTint;\nuniform int alphaMode;\nuniform vec3 lightlevel;\n\nuniform float time;\n\nuniform float yaw;\nuniform float pitch;\nuniform float externalScale;\n\nuniform float lightmix;\nuniform float opacity;\n\n\n\nin vec3 vRysmPosition; in vec2 vRysmUv; in vec4 vRysmColor; out vec4 fragColor;\n\nfloat rand2d(vec2 x) {\n    return fract(sin(mod(dot(x, vec2(12.9898, 78.233)), 3.14)) * 43758.5453);\n}\n\nmat4 rotationMatrix(vec3 axis, float angle)\n{\n\n    axis = normalize(axis);\n    float s = sin(angle);\n    float c = cos(angle);\n    float oc = 1.0 - c;\n    \n    return mat4(oc * axis.x * axis.x + c,           oc * axis.x * axis.y - axis.z * s,  oc * axis.z * axis.x + axis.y * s,  0.0,\n                oc * axis.x * axis.y + axis.z * s,  oc * axis.y * axis.y + c,           oc * axis.y * axis.z - axis.x * s,  0.0,\n                oc * axis.z * axis.x - axis.y * s,  oc * axis.y * axis.z + axis.x * s,  oc * axis.z * axis.z + c,           0.0,\n                0.0,                                0.0,                                0.0,                                1.0);\n}\n\nvoid main (void) \n{\n    vec4 light = vRysmColor;\n    vec4 mask = texture(texture0, vRysmUv);\n    if(mask.a<0.1) discard;\n    if(alphaMode==1 && mask.a<0.99) discard;\n    if(alphaMode==2 && mask.a>=0.99) discard;\n    light.rgb *= lightlevel;\n    \n    float oneOverExternalScale = 1.0/externalScale;\n    \n    float uvtiles = 16.0;\n    \n    // background colour\n    vec4 col = vec4(0.1,0.0,0.0,1.0);\n    \n    float pulse = mod(time,400.0)/400.0;\n    \n    col.g = sin(pulse*M_PI*2.0) * 0.075 + 0.225;\n    col.b = cos(pulse*M_PI*2.0) * 0.05 + 0.3;\n    \n    col.rgb = mix(spaceTint.rgb == vec3(-1.0) ? col.rgb : spaceTint.rgb, mask.rgb, spaceTint.a);\n    // get ray from camera to fragment\n    vec4 dir = normalize(vec4( -vRysmPosition, 0));\n\n\t// rotate the ray to show the right bit of the sphere for the angle\n\tfloat sb = sin(pitch);\n\tfloat cb = cos(pitch);\n\tdir = normalize(vec4(dir.x, dir.y * cb - dir.z * sb, dir.y * sb + dir.z * cb, 0));\n\t\n\tfloat sa = sin(-yaw);\n\tfloat ca = cos(-yaw);\n\tdir = normalize(vec4(dir.z * sa + dir.x * ca, dir.y, dir.z * ca - dir.x * sa, 0));\n\t\n\tvec4 ray;\n\t\n\t// draw the layers\n\tfor (int i=0; i<16; i++) {\n\t\tfloat mult = float(16-i);\n\t\n\t\t// get semi-random stuff\n\t\tfloat j = float(i + 7);\n\t\tfloat rand1 = (j * j * 4321.0 + j * 8.0) * 2.0;\n\t\tfloat k = j + 1.0;\n\t\tfloat rand2 = (k * k * k * 239.0 + k * 37.0) * 3.6;\n\t\tfloat rand3 = rand1 * 347.4 + rand2 * 63.4;\n\t\t\n\t\t// random rotation matrix by random rotation around random axis\n\t\tvec3 axis = normalize(vec3(sin(rand1), sin(rand2) , cos(rand3)));\n\t\t\n\t\t// apply\n\t\tray = dir * rotationMatrix(axis, mod(rand3, 2.0*M_PI));\n\t\t\n\t\t// calcuate the UVs from the final ray\n\t\tfloat rawu = 0.5 + (atan(ray.z,ray.x)/(2.0*M_PI));\n\t\tfloat rawv = 0.5 + (asin(ray.y)/M_PI);\n\t\t\n\t\t// get UV scaled for layers and offset by time;\n\t\tfloat scale = mult*0.5 + 2.75;\n\t\tfloat u = rawu * scale * externalScale;\n\t\t//float v = (rawv + time * 0.00006) * scale * 0.6;\n\t\tfloat v = (rawv + time * 0.0002 * oneOverExternalScale) * scale * 0.6 * externalScale;\n\t\t\n\t\tvec2 tex = vec2( u, v );\n\t\t\n\t\t// tile vRysmPosition of the current vRysmUv\n\t\tfloat tu = mod(floor(u*uvtiles),uvtiles);\n\t\tfloat tv = mod(floor(v*uvtiles),uvtiles); \n\t\t\n\t\t// get pseudorandom variants\n\t\tint symbol = int(rand2d(vec2(tu, tv + float(i) * 10.0)) * cosmicoutof);\n\t\t\n\t\t// if it's an icon, then add the colour!\n\t\tif (symbol >= 0 && symbol < cosmiccount) {\n\t\t\t// Only occupied tiles need an orientation (10 of 101 symbols).\n\t\t\tint rotation = int(mod(pow(tu,tv) + tu + 3.0 + tv*float(i), 8.0));\n\t\t\tbool flip = rotation >= 4;\n\t\t\tif (flip) rotation -= 4;\n\t\t\t\n\t\t\tvec2 cosmictex = vec2(1.0,1.0);\n\t\t\tvec4 tcol = vec4(1.0,0.0,0.0,1.0);\n\t\t\t\n\t\t\t// get vRysmUv within the tile\n\t\t\tfloat ru = clamp(mod(u,1.0)*uvtiles - tu, 0.0, 1.0);\n\t\t\tfloat rv = clamp(mod(v,1.0)*uvtiles - tv, 0.0, 1.0);\n\t\t\t\n\t\t\tif (flip) {\n\t\t\t\tru = 1.0 - ru;\n\t\t\t}\n\t\t\t\n\t\t\tfloat oru = ru;\n\t\t\tfloat orv = rv;\n\n\t\t\t// rotate uvs if necessary\n\t\t\tif (rotation == 1) {\n\t\t\t\toru = 1.0-rv;\n\t\t\t\torv = ru;\n\t\t\t} else if (rotation == 2) {\n\t\t\t\toru = 1.0-ru;\n\t\t\t\torv = 1.0-rv;\n\t\t\t} else if (rotation == 3) {\n\t\t\t\toru = rv;\n\t\t\t\torv = 1.0-ru;\n\t\t\t}\n\t\t\t\n\t\t\ttcol = texture(stars, vec2(oru, (orv + float(symbol)) / float(cosmiccount)));\n\t\t\t// set the alpha, blending out at the bunched ends\n\t\t\tfloat a = tcol.r * (0.5 + (1.0/mult) * 1.0) * (1.0-smoothstep(0.15, 0.48, abs(rawv-0.5)));\n\t\t\t\n\t\t\t// get fancy colours\n\t\t\tfloat r = (mod(rand1, 29.0)/29.0) * 0.3 + 0.4;\n\t    \tfloat g = (mod(rand2, 35.0)/35.0) * 0.4 + 0.6;\n\t    \tfloat b = (mod(rand1, 17.0)/17.0) * 0.3 + 0.7;\n\t\t\t\n\t\t\t// mix the colours\n\t\t\t//col = col*(1-a) + vec4(r,g,b,1)*a;\n\t\t\tvec3 astral = astralTint.rgb == vec3(-1.0) ? vec3(r,g,b) : astralTint.rgb;\n\t\t\tcol = col + vec4(mix(astral, col.rgb, astralTint.a),1)*a;\n\t\t}\n\t}\n\n\t// apply lighting\n    vec3 shade = light.rgb * (lightmix) + vec3(1.0-lightmix,1.0-lightmix,1.0-lightmix);\n    col.rgb *= shade;\n    \n    // apply mask\n    col.a *= mask.a * opacity;\n    \n\tcol = clamp(col,0.0,1.0);\n\t\n    fragColor = col;\n}\n\n";
    const cosmicPreviewDefaults = [{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1,"frames":[{"index":0,"time":7},1,2,3]}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1,"frames":[{"index":0,"time":4},1,{"index":0,"time":9},2,{"index":0,"time":7},3]}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1,"frames":[{"index":0,"time":16},1,1,1,2,2,3,4,3,4,3,2,2,1,1,1]}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1,"frames":[{"index":0,"time":13},1,{"index":0,"time":10},3,{"index":0,"time":5},2,{"index":0,"time":15},4]}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1,"frames":[{"index":0,"time":34},1,2,3]}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1,"frames":[{"index":0,"time":18},1,{"index":0,"time":4},3,{"index":0,"time":14},2]}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":2}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":1,"frames":[1,2,3,2,3,2,1,{"index":0,"time":22},4,5,6,5,6,5,4,{"index":0,"time":31},1,2,3,2,1,{"index":0,"time":12}]}}},{"src":"data:image/png;base64,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","meta":{"animation":{"frametime":2}}}];
    function animationFrame(meta,count,ticks) {
        const animation=meta?.animation;if(!animation)return 0;
        const duration=animation.frametime ?? 1;
        const frames=(animation.frames || Array.from({length:count},(_,index)=>index)).map(frame=>typeof frame==='number'?{index:frame,time:duration}:{index:frame.index,time:frame.time ?? duration});
        if(!frames.length || frames.length>4096 || frames.some(f=>!Number.isInteger(f.index)||f.index<0||f.index>=count||!Number.isInteger(f.time)||f.time<1||f.time>12000))throw new Error('星空动画元数据无效');
        let remaining=((Math.floor(ticks)%frames.reduce((n,f)=>n+f.time,0))+frames.reduce((n,f)=>n+f.time,0))%frames.reduce((n,f)=>n+f.time,0);
        for(const frame of frames){if(remaining<frame.time)return frame.index;remaining-=frame.time;}return 0;
    }
    function cosmicAtlas(preview,effect) {
        if(preview.atlases.has(effect))return preview.atlases.get(effect);
        const canvas=document.createElement('canvas');canvas.width=16;canvas.height=16;
        const atlas={canvas,texture:new THREE.CanvasTexture(canvas),sprites:[],last:[],count:0};preview.atlases.set(effect,atlas);
        atlas.texture.magFilter=THREE.NearestFilter;atlas.texture.minFilter=THREE.NearestFilter;atlas.texture.generateMipmaps=false;atlas.texture.flipY=false;
        let resources=cosmicPreviewDefaults;
        try { if(effect) {
            const root=modelRoot(),config=preview.cosmic[effect];
            if(!root || !config || !Array.isArray(config.textures) || !config.textures.length || config.textures.length>64)throw new Error('星空效果未声明或纹理数量无效: '+effect);
            const fs=require('fs'),path=require('path');
            resources=config.textures.map(name=>{
                if(typeof name!=='string'||!name.endsWith('.png')||/[\\:\x00-\x1f]/.test(name)||name.split('/').some(part=>!part||part==='.'||part==='..')||/[\\/:\x00-\x1f]/.test(effect)||effect.includes('..'))throw new Error('星空资源路径无效');
                const file=path.join(root,'rysm','cosmic',effect,name),bytes=fs.readFileSync(file);if(bytes.length>33554432)throw new Error('星空纹理过大');
                return {src:'data:image/png;base64,'+bytes.toString('base64'),meta:fs.existsSync(file+'.mcmeta')?JSON.parse(fs.readFileSync(file+'.mcmeta','utf8')):null};
            });
        }} catch(error) { Blockbench.showQuickMessage(error.message+'；使用默认星空');resources=cosmicPreviewDefaults; }
        const load=resources=>Promise.all(resources.map(resource=>new Promise((resolve,reject)=>{
            const image=new Image();image.onload=()=>{if(image.width<1||image.width>512||image.height%image.width||image.height/image.width>256){reject(new Error('星空尺寸无效'));return;}resolve({image,meta:resource.meta});};image.onerror=()=>reject(new Error('星空 PNG 加载失败'));image.src=resource.src;
        }))).then(sprites=>{
            if(scriptPreview!==preview)return;
            if(sprites.some(s=>s.image.width!==sprites[0].image.width)||sprites.reduce((n,s)=>n+s.image.width*s.image.height*4,0)>33554432)throw new Error('星空纹理尺寸不一致或超过 32 MiB');
            sprites.forEach(sprite=>animationFrame(sprite.meta,sprite.image.height/sprite.image.width,0));
            atlas.sprites=sprites;atlas.count=sprites.length;canvas.width=sprites[0].image.width;canvas.height=canvas.width*sprites.length;
        });
        load(resources).catch(error=>{
            if(scriptPreview!==preview)return;
            Blockbench.showQuickMessage(error.message+'；使用默认星空');
            if(resources!==cosmicPreviewDefaults)load(cosmicPreviewDefaults).catch(error=>{if(scriptPreview===preview){stopScriptPreview();Blockbench.showQuickMessage(error.message);}});
        });
        return atlas;
    }
    function applyScriptPreview(preview,data,mock) {
        for(const [mesh,saved] of preview.meshes){mesh.position.copy(saved.position);mesh.quaternion.copy(saved.quaternion);mesh.scale.copy(saved.scale);}
        const viewport=typeof Preview!=='undefined'?Preview.selected:null;
        for(const transform of data.transforms) {
            const group=Group.all.find(g=>g.name===transform.name);if(!group?.mesh)throw new Error('预览中不存在组: '+transform.name);
            const mesh=group.mesh,[x,y,z]=transform.values;
            if(transform.op==='move')mesh.position.add(new THREE.Vector3(x,y,z));
            if(transform.op==='rotate'){mesh.rotateX(x*Math.PI/180);mesh.rotateY(y*Math.PI/180);mesh.rotateZ(z*Math.PI/180);}
            if(transform.op==='scale')mesh.scale.multiply(new THREE.Vector3(x,y,z));
        }
        for(const [name,properties] of Object.entries(preview.groups)) {
            const group=Group.all.find(g=>g.name===name);if(!group?.mesh)continue;
            if(properties.facingCamera && viewport?.camera){
                const rotation=viewport.camera.getWorldQuaternion(new THREE.Quaternion()),parent=group.mesh.parent?.getWorldQuaternion(new THREE.Quaternion());
                group.mesh.quaternion.copy(parent?parent.invert().multiply(rotation):rotation);
            }
        }
        if(!preview.effectMeshes) {
            preview.effectMeshes=[];
            for(const cube of Cube.all) {
                if(!cube.mesh)continue;const chain=[];for(let parent=cube.parent;parent instanceof Group;parent=parent.parent)chain.unshift(parent);
                let cosmic;for(const group of chain)if(Object.hasOwn(preview.groups[group.name] || {},'cosmic'))cosmic=preview.groups[group.name].cosmic;
                if(!cosmic)continue;
                const properties=typeof cosmic==='object'?cosmic:{},atlas=cosmicAtlas(preview,properties.effect || '');
                const tint=(color,alpha)=>new THREE.Vector4(...(color?new THREE.Color('#'+color).toArray():[-1,-1,-1]),Number(alpha || 0)/100);
                const materials=(Array.isArray(cube.mesh.material)?cube.mesh.material:[cube.mesh.material]).map(original=>{
                    let map=original?.map;
                    if(!map){const white=document.createElement('canvas');white.width=white.height=1;white.getContext('2d').fillStyle='white';white.getContext('2d').fillRect(0,0,1,1);map=new THREE.CanvasTexture(white);preview.atlases.set('white:'+cube.uuid,{texture:map});}
                    const material=new THREE.ShaderMaterial({glslVersion:THREE.GLSL3,transparent:true,side:original?.side ?? THREE.DoubleSide,
                        uniforms:{texture0:{value:map},stars:{value:atlas.texture},cosmiccount:{value:1},cosmicoutof:{value:101},spaceTint:{value:tint(properties.sc,properties.sa)},astralTint:{value:tint(properties.ac,properties.aa)},alphaMode:{value:0},lightlevel:{value:new THREE.Vector3(1,1,1)},time:{value:0},yaw:{value:0},pitch:{value:0},externalScale:{value:1},lightmix:{value:0},opacity:{value:1}},
                        vertexShader:'out vec3 vRysmPosition; out vec2 vRysmUv; out vec4 vRysmColor; void main(){ vec4 view=modelViewMatrix*vec4(position,1.0); vRysmPosition=view.xyz; vRysmUv=uv; vRysmColor=vec4(1.0); gl_Position=projectionMatrix*view; }',fragmentShader:cosmicPreviewFragment});
                    preview.materials.push(material);preview.effectMeshes.push({material,atlas});return material;
                });cube.mesh.material=Array.isArray(cube.mesh.material)?materials:materials[0];
            }
        }
        for(const atlas of preview.atlases.values())if(atlas.sprites?.length) {
            const ctx=atlas.canvas.getContext('2d'),size=atlas.canvas.width;
            atlas.sprites.forEach((sprite,index)=>{const frame=animationFrame(sprite.meta,sprite.image.height/size,data.ticks);if(frame!==atlas.last[index]){ctx.clearRect(0,index*size,size,size);ctx.drawImage(sprite.image,0,frame*size,size,size,0,index*size,size,size);atlas.last[index]=frame;atlas.texture.needsUpdate=true;}});
        }
        for(const {material,atlas} of preview.effectMeshes){material.uniforms.time.value=Math.floor(data.ticks);material.uniforms.yaw.value=Number(mock.camera.yaw)*Math.PI/180;material.uniforms.pitch.value=Number(mock.camera.pitch)*Math.PI/180;material.uniforms.cosmiccount.value=Math.max(1,atlas.count);}
    }
    let scriptProperty,scriptProjectProperty,scriptOwnedProperty;

    if (typeof Plugin === 'undefined' && typeof module !== 'undefined') {
        module.exports = {read, encode, readEffect, encodeEffect, inheritValues, sectorLines, capsuleLines,generatedGroups,scriptPath,inputEdges,animationFrame,previewWorker,startScriptPreview,stopScriptPreview,cosmicPreviewFragment,cosmicPreviewDefaults,writeScripts,saveUserScript,setScriptName,scriptSource};
        return;
    }
    Plugin.register('reimagined_ysm_physics_groups', {
        title: 'Reimagined YSM 物理组编辑器', author: 'Reimagined YSM contributors', icon: 'tune', version: '3.1.0',
        description: '分组编辑与视口辅助；支持物理骨、尾骨、星空着色器、描边及单面厚度。', variant: 'both', min_version: '5.0.0',
        onload() {
            scriptProperty = new Property(Group,'object','rysm_properties',{default:{}});
            scriptOwnedProperty = new Property(Group,'boolean','rysm_script_owned',{default:false});
            scriptProjectProperty = new Property(ModelProject,'object','rysm_scripts',{default:{}});
            Blockbench.on('save_project',saveScripts);
            // Bedrock re-sanitizes group names on import, project load and undo.
            originalSanitize = Group.prototype.sanitizeName;
            sanitize = function (...args) {
                if (supported() && (joint.test(this.name) || marker.test(this.name) || effect.test(this.name))) return this.name;
                return originalSanitize.apply(this, args);
            };
            Group.prototype.sanitizeName = sanitize;
            panel = makeFloatingInspector();
            menu = new BarMenu('openysm_physics', [{name: '打开物理组检查器', icon: 'icon-vertexsnap', click: showPanel}, {name:'编辑脚本组属性',icon:'code',click:editScriptProperties}, {name:'JavaScript 编辑与预览',icon:'code',click:editScripts}, {name:'停止脚本预览',icon:'stop',click:stopScriptPreview}, {name:'导入模型脚本属性',icon:'download',click:importScriptGroups}, {name:'导入旧组名并生成脚本',icon:'sync',click:importLegacyGroups}, {name:'写入模型 rysm 文件夹',icon:'save',click:writeScripts}],
                {name: 'Reimagined YSM 物理', condition: () => supported() && (Modes.edit || Modes.animate)});
            window.addEventListener('mousedown', pickPhysics, true);
            window.addEventListener('mouseup', finishPhysicsPick, true);
            window.addEventListener('blur', clearPickedGesture);
            MenuBar.update();
            frame = requestAnimationFrame(tick);
        },
        onunload() {
            stopScriptPreview();scriptEditor?.hide();scriptProjectProperty?.delete();scriptOwnedProperty?.delete();
            Blockbench.removeListener('save_project',saveScripts);
            scriptProperty?.delete();
            cancelAnimationFrame(frame);
            window.removeEventListener('mousedown', pickPhysics, true);
            window.removeEventListener('mouseup', finishPhysicsPick, true);
            window.removeEventListener('blur', clearPickedGesture);
            clearPickedGesture();
            disposeOverlay();
            clearGizmos();
            menu?.delete();
            panel?.delete();
            MenuBar.update();
            if (Group.prototype.sanitizeName === sanitize) Group.prototype.sanitizeName = originalSanitize;
        }
    });
})();
