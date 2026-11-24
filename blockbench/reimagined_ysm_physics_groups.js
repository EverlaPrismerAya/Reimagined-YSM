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
    const comparison = new RegExp(`^(?:v|variable)\\.[a-z_][a-z_0-9]*(?:\\.[a-z_][a-z_0-9]*)*\\s*(?:>=|<=|!=|=|>|<)\\s*(${number})$`, 'i');
    const joint = /^(?:ysmPhys|ysmTail)(?:#([^@]+))?@([^@]+)$/;
    const marker = /^(?:ysmPhys(?:End|C)|ysmItem)@[^@]+$/;
    const end = /^ysmPhysEnd@[^@]+$/;
    const float = text => decimal.test(text) && Number.isFinite(Math.fround(Number(text)));

    function validate(key, value) {
        if (key === 'enable' || key === 'disable') {
            const items = value.split(',').map(s => s.trim());
            if (items.every(s => /^[^\s(),=<>!@]+$/.test(s)) || items.every(s => {
                const match = comparison.exec(s);
                return match && /^(?:v|variable)\./.test(s) && float(match[1]);
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
        let oldName = bone.name;
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
                if (Project !== project || Group.first_selected !== bone || bone.name !== oldName || bone.locked || !bone.export) throw new Error('目标骨骼已改变，请重新选择。');
                const next = uniqueName(remove ? name.value.trim() : proposed(), bone);
                if (next === bone.name) return;
                Undo.initEdit({outliner:true, groups:[bone]});
                bone.name = next;
                Undo.finishEdit('Edit Reimagined YSM group effect', {outliner:true, groups:[bone]});
                oldName = bone.name;
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
        if (!(bone instanceof Group) || !joint.test(bone.name)) return {};
        const tail = bone.name.startsWith('ysmTail');
        const parent = bone.parent instanceof Group && bone.parent.name.startsWith('ysmTail') === tail ? inherited(bone.parent) : {};
        return inheritValues(parent, read(bone.name).values);
    }

    let menu, panel, frame, inspectorKey, draw, gizmos, originalSanitize, sanitize;
    const supported = () => ['bedrock', 'bedrock_old'].includes(Format?.id);
    function uniqueName(base, except) {
        let name = base;
        for (let i = 2; Group.all.some(group => group !== except && group.name.toLowerCase() === name.toLowerCase()); i++) {
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
            const name = joint.exec(bone.name)?.[2];
            if (!name) return;
            const normalName = uniqueName(name.startsWith('ysmPhys') ? `bone_${name}` : name, bone);
            const ends = bone.children.filter(child => child instanceof Group && end.test(child.name));
            const parent = bone.parent;
            const repair = parent instanceof Group && joint.test(parent.name) && !parent.children.some(child =>
                child !== bone && child instanceof Group && child.export && (joint.test(child.name) || end.test(child.name)));
            if (ends.some(child => child.locked) || (repair && parent.locked)) {
                status.textContent = '请先解锁受影响的末端或父物理组。';
                return;
            }
            const removable = ends.filter(child => !child.children.length && !Animation.all.some(animation =>
                animation.animators[child.uuid]?.keyframes?.length));
            const animations = Animation.all.filter(animation => removable.some(child => animation.animators[child.uuid]));
            const groups = [bone, ...ends.filter(child => !removable.includes(child))];
            Undo.initEdit({outliner:true, groups:[bone, ...ends], selection:true, animations});
            bone.name = normalName;
            for (const child of ends) {
                if (removable.includes(child)) { child.remove(false); continue; }
                const label = child.name.slice('ysmPhysEnd@'.length);
                child.name = uniqueName(label.startsWith('ysmPhys') ? `bone_${label}` : label, child);
            }
            if (repair) {
                const pivot = distance(parent.origin, bone.origin) > minLength ? bone.origin.slice() : defaultEnd(parent.origin);
                const name = uniqueName(`ysmPhysEnd@${joint.exec(parent.name)[2]}_end`);
                groups.push(new Group({name, origin:pivot}).addTo(parent).init());
            }
            Undo.finishEdit('Remove Reimagined YSM physics', {outliner:true, groups, selection:true, animations});
            inspectorKey = undefined;
        };
        return button;
    }

    function edit(bone, create = false, kind = 'ysmPhys') {
        const project = Project;
        let oldName = bone.name;
        const model = read(oldName);
        const parentValues = inherited(bone.parent);
        const children = bone.children.filter(child => child instanceof Group);
        const branches = children.filter(child => joint.test(child.name));
        const ends = children.filter(child => end.test(child.name));
        const endpoint = ends[0];
        if (branches.some(child => !child.export) || (endpoint && (!endpoint.export || endpoint.locked))) {
            throw new Error('请先将物理子组设为可导出，并确保现有末端可导出、未锁定。');
        }
        if (ends.length > 1 || endpoint?.children.length || (endpoint && branches.length)) {
            throw new Error('请先修正拓扑：分叉组不能有末端，叶节点只能有一个空末端组。');
        }
        const parentEnds = joint.test(bone.parent?.name) ? bone.parent.children.filter(child => child instanceof Group && end.test(child.name)) : [];
        const movedEnd = create && !endpoint && !branches.length && parentEnds.length === 1 ? parentEnds[0] : null;
        if (parentEnds.length && (!movedEnd || movedEnd.locked || !movedEnd.export || movedEnd.children.length || bone.parent.locked ||
            bone.parent.children.some(child => child !== bone && joint.test(child.name)))) {
            throw new Error('无法接入：父物理组必须至多有一个可移动的空末端，且不能同时有其他物理子组。');
        }
        const origin = bone.origin.slice();
        if (branches.some(child => distance(origin, child.origin) <= minLength) ||
            (joint.test(bone.parent?.name) && distance(origin, bone.parent.origin) <= minLength)) {
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
        const inheritedParameters = () => bone.parent?.name?.startsWith('ysmTail') === (type.value === 'ysmTail') ? parentValues : {};
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
                if (Project !== project || !Group.all.includes(bone) || bone.name !== oldName || bone.locked) throw new Error('目标骨骼已改变，请重新选择骨骼。');
                const newName = create ? uniqueName(proposedName(), bone) : proposedName();
                const taken = (label, except) => Group.all.some(other => other !== except && other.name.toLowerCase() === label.toLowerCase());
                if (taken(newName, bone)) throw new Error('生成的骨骼名已存在。');
                if (!create && newName === bone.name) return;
                const pivot = endpoint || branches.length ? null : movedEnd
                    ? (distance(origin, movedEnd.origin) > minLength ? movedEnd.origin.slice()
                        : origin.map((n, i) => 2 * n - bone.parent.origin[i])) : defaultEnd(origin);
                const endName = pivot && !movedEnd ? uniqueName(`ysmPhysEnd@${name.value.trim()}_end`) : null;
                const groups = [bone, movedEnd].filter(Boolean);
                Undo.initEdit({outliner: true, groups: groups.slice()});
                bone.name = newName;
                if (movedEnd) {
                    movedEnd.origin.splice(0, 3, ...pivot);
                    movedEnd.addTo(bone);
                    Canvas.updateAllBones([movedEnd]);
                }
                else if (pivot) groups.push(new Group({name: endName, origin: pivot}).addTo(bone).init());
                bone.isOpen = true;
                Undo.finishEdit('Edit Reimagined YSM physics group', {outliner: true, groups});
                oldName = bone.name;
                inspectorKey = create ? undefined : selectionKey(bone);
            } catch (error) {
                if (create) throw error;
                status.textContent = error.message;
            }
        }
        if (create) { commit(); return; }
        add('p', '有效输入立即生效；无效输入保留上次有效值。可使用撤销恢复。');
        root.append(cancelPhysicsButton(bone, status));
        const candidates = children.filter(child => child.export && !child.locked && !child.name.startsWith('ysmPhys'));
        if (candidates.length) {
            add('p', '延长物理链：接入已有直属子骨骼。');
            for (const child of candidates) {
                const extend = add('button', `接入 ${child.name}`);
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
        const cube = colliderCube(group);
        group.isOpen = true;
        cube.select(new MouseEvent('click'), true);
        Undo.finishEdit('Add Reimagined YSM collision box', {outliner:true, elements:[cube], selection:true});
    }

    function createCollider() {
        const parent = Group.first_selected;
        for (let node = parent; node instanceof Group; node = node.parent) {
            if (node.locked || !node.export || joint.test(node.name) || marker.test(node.name)) {
                Blockbench.showQuickMessage('请选中可导出的普通骨骼，或取消选择后在模型根部创建。');
                return;
            }
        }
        const origin = parent?.origin.slice() ?? [0, 0, 0];
        Undo.initEdit({outliner:true, groups:[], elements:[], selection:true});
        const group = new Group({name:uniqueName(`ysmPhysC@${parent ? parent.name.replace(/@/g, '_') + '_' : ''}collider`), origin})
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
        title.textContent = `${group.name} · 无 UV 方块也可从这里选中，再用原生移动、缩放、旋转工具编辑。`;
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
            if (!usable(bone) || !joint.test(bone.name)) return null;
            if (!parameters.has(bone)) {
                try { parameters.set(bone, inheritValues(resolve(bone.parent), read(bone.name).values)); }
                catch { parameters.set(bone, null); }
            }
            return parameters.get(bone);
        }
        const segments = [];
        const distinct = (a, b) => Math.hypot(...a.origin.map((x, i) => x - b.origin[i])) > 16e-6;
        function chain(bone) {
            const children = bone.children.filter(child => resolve(child));
            const ends = bone.children.filter(child => usable(child) && end.test(child.name));
            const valid = children.filter(child => distinct(child, bone)).map(child => [child, chain(child)]).filter(([, result]) => result);
            if (valid.length ? ends.length : (ends.length !== 1 || ends[0].children.length || !distinct(ends[0], bone))) return null;
            const radius = Number(resolve(bone).c ?? fields.c[1]);
            return valid.length ? valid.flatMap(([child, result]) => [{bone, target:child, radius}, ...result])
                : [{bone, target:ends[0], radius}];
        }
        for (const bone of groups) if (resolve(bone) && !resolve(bone.parent)) segments.push(...(chain(bone) ?? []));
        const cubes = [];
        if (segments.length) for (const group of groups) if (usable(group) && collider.test(group.name)) {
            let ignored = false;
            for (let ancestor = group.parent; ancestor instanceof Group; ancestor = ancestor.parent) {
                if (resolve(ancestor) || end.test(ancestor.name)) { ignored = true; break; }
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
        const groups = Group.all.filter(group => group.name.startsWith('ysmPhys') || group.name.startsWith('ysmTail'));
        const key = JSON.stringify([Project.uuid, showBones, showVolumes, groups.map(group => [group.uuid, group.name,
            group.export, group.origin, collider.test(group.name) ? colliderParentKey(group) : group.parent?.uuid, collider.test(group.name) || end.test(group.name)
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
                    if (marker.test(group.name)) break;
                    if (joint.test(group.name)) { target = group; break; }
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
        return JSON.stringify(bone ? [Project.uuid, bone.uuid, bone.name, bone.origin, bone.locked, bone.export,
            bone.parent?.name, Cube.selected?.[0]?.uuid, bone.children.map(child => [child.uuid, child.name, child.export, child.locked,
                end.test(child.name) ? [child.origin, child.locked, child.children?.length] : null])] : []);
    }

    const showPanel = () => panel.show();
    function tick() {
        frame = requestAnimationFrame(tick);
        updateOverlay(supported() && (Modes.edit || Modes.animate));
        const selected = supported() && Modes.edit ? Group.first_selected : null;
        const cubeParent = supported() && Modes.edit ? Cube.selected?.[0]?.parent : null;
        const bone = collider.test(cubeParent?.name) ? cubeParent : selected;
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
                if (/^ysm(?:Cosmic|Outline)/.test(bone.name)) editEffect(bone, readEffect(bone.name).kind);
                else if (joint.test(bone.name)) edit(bone);
                else if (collider.test(bone.name)) inspectCollider(bone);
                else if (end.test(bone.name)) throw new Error('这是物理末端标记，请选择父物理骨骼。');
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
                    const isItem = /^ysmItem@[^@]+$/.test(bone.name);
                    itemButton.textContent = isItem ? '取消单面厚度' : '启用单面厚度';
                    itemButton.onclick = () => {
                        try {
                            if (Group.first_selected !== bone || bone.locked) return;
                            const cubes = bone.children.filter(child => child instanceof Cube && child.export);
                            if (!isItem && (cubes.length !== 1 || Object.values(cubes[0].faces).filter(face => face.texture !== null).length !== 1)) {
                                throw new Error('单面厚度需要组内恰好一个可导出方块，且只有一个面分配了纹理。');
                            }
                            Undo.initEdit({outliner:true, groups:[bone]});
                            bone.name = uniqueName(isItem ? bone.name.slice(8) : `ysmItem@${bone.name}`, bone);
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
                    const heading = document.createElement('h3'); heading.textContent = bone.name;
                    const note = document.createElement('p'); note.textContent = '将当前组设为以下类型；已有特殊组请用父子组嵌套组合。';
                    panel.node.replaceChildren(heading, note, physics, effects, help, status);
                }
            } catch (error) {
                const message = document.createElement('p');
                message.textContent = error.message;
                panel.node.replaceChildren(message);
                if (bone && !bone.locked && bone.export && joint.test(bone.name)) panel.node.append(cancelPhysicsButton(bone, message));
            }
        }
        const outputs = draw?.() ?? [];
        const geometryKey = JSON.stringify([showBones, showVolumes, outputs]);
        if (geometryKey !== drawnKey) {
            drawnKey = geometryKey;
            renderGizmos(outputs);
        }
    }

    if (typeof Plugin === 'undefined' && typeof module !== 'undefined') {
        module.exports = {read, encode, readEffect, encodeEffect, inheritValues, sectorLines, capsuleLines};
        return;
    }
    Plugin.register('reimagined_ysm_physics_groups', {
        title: 'Reimagined YSM 物理组编辑器', author: 'Reimagined YSM contributors', icon: 'tune', version: '2.2.0',
        description: '分组编辑与视口辅助；支持物理骨、尾骨、星空着色器、描边及单面厚度。', variant: 'both', min_version: '5.0.0',
        onload() {
            // Bedrock re-sanitizes group names on import, project load and undo.
            originalSanitize = Group.prototype.sanitizeName;
            sanitize = function (...args) {
                if (supported() && (joint.test(this.name) || marker.test(this.name) || effect.test(this.name))) return this.name;
                return originalSanitize.apply(this, args);
            };
            Group.prototype.sanitizeName = sanitize;
            panel = makeFloatingInspector();
            menu = new BarMenu('openysm_physics', [{name: '打开物理组检查器', icon: 'icon-vertexsnap', click: showPanel}],
                {name: 'Reimagined YSM 物理', condition: () => supported() && (Modes.edit || Modes.animate)});
            window.addEventListener('mousedown', pickPhysics, true);
            window.addEventListener('mouseup', finishPhysicsPick, true);
            window.addEventListener('blur', clearPickedGesture);
            MenuBar.update();
            frame = requestAnimationFrame(tick);
        },
        onunload() {
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
