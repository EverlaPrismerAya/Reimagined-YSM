"""Convert one folder-format YSM model; standard library only, no source writes."""
import json
import math
import re
import shutil
import tempfile
from pathlib import Path

PREFIXES = {
    'ysmPhysEnd': 'end', 'ysmPhysC': 'collider', 'ysmPhys': 'physics',
    'ysmTail': 'physics', 'ysmCosmic': 'cosmic', 'ysmOutline': 'outline',
    'FrameAnimation': 'frameAnimation', 'FacingCamera': 'facingCamera', 'ysmItem': 'item',
}


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def dump(value):
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)


def parameters(text):
    parts, start, depth = [], 0, 0
    for i, char in enumerate(text):
        if char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
        elif char == ',' and depth == 0:
            parts.append(text[start:i]); start = i + 1
        if depth < 0 or depth > 1:
            raise ValueError('参数括号无效')
    if depth:
        raise ValueError('参数括号不匹配')
    parts.append(text[start:])
    result = {}
    for part in parts:
        key, sep, value = part.partition('=')
        key, value = key.strip(), value.strip()
        if not sep or not key or not value or key in result:
            raise ValueError('无效或重复参数: ' + part)
        result[key] = value
    return result


def properties(name):
    prefix = next((p for p in PREFIXES if name.startswith(p)), None)
    if prefix is None:
        return None
    head, at, label = name.partition('@')
    if '@' in label or (at and not label):
        raise ValueError('组名无效: ' + name)
    kind, hashmark, args = head.partition('#')
    if kind != prefix:
        raise ValueError('组名前缀无效: ' + name)
    values = parameters(args) if hashmark else {}
    prop = PREFIXES[prefix]
    if prop in ('end', 'collider', 'item', 'facingCamera'):
        if values or hashmark or (prop in ('end', 'collider') and not at):
            raise ValueError('组名格式无效: ' + name)
        return {prop: True}
    allowed = {
        'physics': {'r', 'g', 'gw', 's', 'a', 'c', 'air', 'bc', 'enable', 'disable'},
        'cosmic': {'sc', 'sa', 'ac', 'aa'}, 'outline': {'color', 'width'},
        'frameAnimation': {'count', 'tick', 'macro', 'macroRepeat'},
    }[prop]
    if values.keys() - allowed:
        raise ValueError('未知参数: ' + name)
    if prop == 'physics' and not at:
        raise ValueError('物理组名缺少 @名称: ' + name)
    for key, value in values.items():
        if key in ('enable', 'disable'):
            if not value.startswith('(') or not value.endswith(')') or not value[1:-1].strip():
                raise ValueError('启用/禁用条件必须使用非空括号: ' + name)
            conditions = [item.strip() for item in value[1:-1].split(',')]
            comparison = r'(?:v|variable|rysmjs)\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\s*(?:>=|<=|!=|=|>|<)\s*[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?'
            flags = [bool(re.fullmatch(comparison, item, re.ASCII)) for item in conditions]
            if any(flags) and not all(flags) or any(not flag and not re.fullmatch(r'[^\s(),=<>!]+', item) for flag, item in zip(flags, conditions)):
                raise ValueError('启用/禁用条件无效或混合动画与变量: ' + name)
        elif key in ('bc', 'macroRepeat'):
            if value not in ('true', 'false'):
                raise ValueError('布尔参数无效: ' + name)
        elif key in ('sc', 'ac', 'color'):
            if not (key == 'color' and value == 'auto') and not re.fullmatch('[0-9a-fA-F]{6}', value):
                raise ValueError('颜色必须为六位十六进制: ' + name)
        elif key == 'macro':
            if not re.fullmatch(r'(?:v|variable|rysmjs)\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*', value, re.ASCII):
                raise ValueError('macro 变量路径无效: ' + name)
        else:
            number = float(value)
            if not math.isfinite(number):
                raise ValueError('参数必须为有限数值: ' + name)
            if key in ('r', 's', 'c', 'air', 'a', 'width', 'sa', 'aa') and number < 0:
                raise ValueError('参数超出范围: ' + name)
            upper = {'a': 180, 'width': 64, 'sa': 100, 'aa': 100}.get(key)
            if upper is not None and number > upper:
                raise ValueError('参数超出范围: ' + name)
    if prop == 'physics':
        if 'g' in values and 'gw' in values:
            raise ValueError('g 和 gw 不能同时使用')
        if prefix == 'ysmTail':
            values['kind'] = 'tail'
    if prop == 'frameAnimation':
        if not re.fullmatch(r'[1-9][0-9]*', values.get('count', '')):
            raise ValueError('帧数必须为正整数')
        if ('tick' in values) == ('macro' in values) or ('tick' in values and float(values['tick']) <= 0):
            raise ValueError('帧动画必须指定正数 tick 或 macro')
    return {prop: values}


def bones(tree):
    if isinstance(tree, dict):
        for bone in tree.get('bones', []) if isinstance(tree.get('bones'), list) else []:
            if isinstance(bone, dict) and isinstance(bone.get('name'), str):
                yield bone['name']
        for value in tree.values():
            yield from bones(value)
    elif isinstance(tree, list):
        for value in tree:
            yield from bones(value)


def strings(tree):
    if isinstance(tree, str):
        yield tree
    elif isinstance(tree, dict):
        for key, value in tree.items():
            yield key
            yield from strings(value)
    elif isinstance(tree, list):
        for value in tree:
            yield from strings(value)


def rewrite(tree, renames):
    if isinstance(tree, str):
        if tree in renames:
            return renames[tree]
        # Replace once, so overlapping names and rename chains cannot cascade.
        quoted = {q + old + q: q + new + q for old, new in renames.items() for q in ('\"', "'")}
        if quoted:
            pattern = '|'.join(re.escape(s) for s in sorted(quoted, key=len, reverse=True))
            return re.sub(pattern, lambda m: quoted[m[0]], tree)
    elif isinstance(tree, dict):
        return {renames.get(k, k): rewrite(v, renames) for k, v in tree.items()}
    elif isinstance(tree, list):
        return [rewrite(v, renames) for v in tree]
    return tree


def model_paths(root):
    """Use the same model scopes as ScriptModel.forModel."""
    if (root / 'ysm.json').is_file():
        files = read_json(root / 'ysm.json')['files']
        player = files['player']['model']
        result = [(player['main'], '')]
        if 'arm' in player:
            result.append((player['arm'], 'arm.'))
        for section in ('vehicles', 'projectiles'):
            items = files.get(section, [])
            if isinstance(items, dict):
                items = items.values()
            for i, item in enumerate(items):
                result.append((item['model'], f'{section}.{i}.'))
        return result
    if (root / 'main.json').is_file() and (root / 'arm.json').is_file():
        return [('main.json', ''), ('arm.json', 'arm.')] + ([('arrow.json', 'projectiles.0.')] if (root / 'arrow.json').is_file() else [])
    raise ValueError('请选择含 ysm.json 或 main.json + arm.json 的单个 YSM 模型文件夹')


def upgrade(source, target):
    source = Path(source).absolute()
    if source.is_symlink() or source.is_junction():
        raise ValueError('不支持符号链接或目录联接')
    source, target = source.resolve(), Path(target).resolve()
    if not source.is_dir():
        raise ValueError('仅支持文件夹格式模型，不接受 ZIP、.ysm 或 .rysm 文件')
    if target.exists() or target.resolve().is_relative_to(source):
        raise ValueError('输出必须是不存在的新文件夹，且不能位于输入文件夹内')
    paths = list(source.rglob('*'))
    if source.is_symlink() or any(p.is_symlink() or p.is_junction() for p in paths):
        raise ValueError('不支持符号链接或目录联接')
    if (source / 'rysm/manifest.json').exists() or (source / 'rysm/scripts/prefix-migrated.js').exists():
        raise ValueError('模型已有 JS manifest，请勿重复升级或混合转换旧 JS API')
    documents = {p.relative_to(source).as_posix(): read_json(p) for p in paths if p.suffix.lower() == '.json' and p.relative_to(source).parts[0] != 'rysm'}
    geometry = []
    for path, scope in model_paths(source):
        normalized = Path(path.replace('\\', '/'))
        if normalized.is_absolute() or '..' in normalized.parts or ':' in path:
            raise ValueError('模型资源路径不安全: ' + path)
        tree = documents[normalized.as_posix()]
        geometry.append((scope, list(bones(tree))))
    names = {name for _, group in geometry for name in group}
    props = {name: properties(name) for name in sorted(names)}
    props = {name: value for name, value in props.items() if value is not None}
    if not props:
        raise ValueError('未找到可升级的旧组名前缀')
    renames, report = {}, []
    all_strings = [s for tree in documents.values() for s in strings(tree)]
    for name in props:
        prefix = next(p for p in PREFIXES if name.startswith(p))
        clean = name.partition('@')[2] if '@' in name else name[len(prefix):].lstrip('_ ')
        unsafe = False
        for text in all_strings:
            if text in names or name not in text:
                continue
            for bone in sorted(names, key=len, reverse=True):
                for q in ('\"', "'"):
                    text = text.replace(q + bone + q, '')
            if name in text:
                unsafe = True
                break
        if not clean.strip() or clean in names or clean in renames.values() or unsafe:
            report.append('保留组名（冲突或不明确引用）: ' + name)
        else:
            renames[name] = clean
    registrations = {}
    for scope, group in geometry:
        for name in group:
            if name in props:
                registrations[scope + renames.get(name, name)] = props[name]
    script = '// Converted from legacy group names.\n' + ''.join(
        'Rysm.model("group_modifier", event => event.modify(' + json.dumps(name, ensure_ascii=False) + ')' +
        ''.join('.' + key + '(' + json.dumps(value, ensure_ascii=False) + ')' for key, value in value.items()) + ');\n'
        for name, value in registrations.items())
    # Stage beside output; failure never publishes a partial model or changes the input.
    with tempfile.TemporaryDirectory(prefix='.ysm-upgrade-', dir=target.parent) as temp:
        staged = Path(temp) / 'model'
        shutil.copytree(source, staged)
        for path, tree in documents.items():
            converted = rewrite(tree, renames)
            if converted != tree:
                (staged / path).write_text(dump(converted) + '\n', encoding='utf-8')
        (staged / 'rysm/scripts').mkdir(parents=True, exist_ok=True)
        (staged / 'rysm/scripts/prefix-migrated.js').write_text(script, encoding='utf-8')
        manifest = {'version': 1, 'scripts': ['scripts/prefix-migrated.js'], 'prefixMigrated': True}
        (staged / 'rysm/manifest.json').write_text(dump(manifest) + '\n', encoding='utf-8')
        (staged / 'rysm/migration-report.txt').write_text('原件保留于: ' + str(source) + '\n' + '\n'.join(report), encoding='utf-8')
        staged.rename(target)
    return f'升级完成：{len(registrations)} 个组绑定，重命名 {len(renames)} 个组名。\n输出：{target}\n' + '\n'.join(report)


def main():
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    from concurrent.futures import ThreadPoolExecutor
    window = tk.Tk()
    window.title('YSM 旧组名升级工具')
    window.geometry('760x420')
    window.minsize(620, 360)
    source, target = tk.StringVar(), tk.StringVar()
    frame = ttk.Frame(window, padding=18)
    frame.pack(fill='both', expand=True)
    frame.columnconfigure(1, weight=1)
    ttk.Label(frame, text='旧组名 → JS 驱动 · 仅文件夹格式 YSM 模型', font=('', 13)).grid(row=0, column=0, columnspan=3, sticky='w', pady=(0, 16))
    def browse_source():
        value = filedialog.askdirectory(title='选择单个 YSM 模型根目录')
        if value:
            source.set(value)
            target.set(str(Path(value).with_name(Path(value).name + '-upgraded')))
    def browse_target():
        value = filedialog.askdirectory(title='选择输出父目录（自动创建新的模型文件夹）')
        if value:
            target.set(str(Path(value) / (Path(source.get()).name + '-upgraded')))
    for row, label, variable, command in ((1, '模型文件夹', source, browse_source), (2, '新输出文件夹', target, browse_target)):
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=6)
        ttk.Entry(frame, textvariable=variable).grid(row=row, column=1, sticky='ew', padx=10)
        ttk.Button(frame, text='浏览…', command=command).grid(row=row, column=2)
    ttk.Label(frame, text='保留原件；输出不可已存在。ZIP / 二进制模型 / 已有 JS manifest 不支持。').grid(row=3, column=0, columnspan=3, sticky='w', pady=12)
    log = tk.Text(frame, height=10, wrap='word', state='disabled')
    log.grid(row=5, column=0, columnspan=3, sticky='nsew', pady=12)
    frame.rowconfigure(5, weight=1)
    pool = ThreadPoolExecutor(max_workers=1)
    def write(message):
        log.configure(state='normal'); log.delete('1.0', 'end'); log.insert('end', message); log.configure(state='disabled')
    def start():
        if not source.get().strip() or not target.get().strip():
            messagebox.showerror('路径未填写', '请选择输入模型和新输出文件夹。'); return
        button.configure(state='disabled'); write('正在升级，请稍候…')
        future = pool.submit(upgrade, source.get(), target.get())
        def poll():
            if not future.done():
                window.after(100, poll); return
            button.configure(state='normal')
            try:
                write(future.result())
            except Exception as error:
                write('升级失败：' + str(error)); messagebox.showerror('升级失败', str(error))
        poll()
    button = ttk.Button(frame, text='生成升级副本', command=start)
    button.grid(row=4, column=0, columnspan=3)
    window.mainloop()
    pool.shutdown(wait=True)


if __name__ == '__main__':
    main()
