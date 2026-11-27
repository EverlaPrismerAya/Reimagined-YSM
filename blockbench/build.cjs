// Rebuild the single-file, offline Blockbench plugin. No package install required.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = __dirname;
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const cm = ['lib/codemirror.js', 'mode/javascript/javascript.js', 'addon/edit/matchbrackets.js',
    'addon/selection/active-line.js', 'addon/hint/show-hint.js'];
const css = ['lib/codemirror.css', 'addon/hint/show-hint.css'].map(name => read('vendor/codemirror/' + name)).join('\n');
const core = cm.shift();
const vendor = `// Offline dependencies: CodeMirror 5.65.16, Acorn 8.14.1 (MIT; see vendor licenses).
    const scriptParser = (() => {
        const exports = {}, module = {exports}, define = undefined;
${read('vendor/acorn/dist/acorn.js')}
        return module.exports;
    })();
    let editorCodeMirror;
    function loadCodeMirror() {
        if (editorCodeMirror) return editorCodeMirror;
        // Isolate UMD globals from Electron's require and Blockbench's editor.
        const host = {};
        (function(module, exports, define) {
${read('vendor/codemirror/' + core)}
        }).call(host, undefined, undefined, undefined);
        (function(CodeMirror, module, exports, define) {
${cm.map(name => read('vendor/codemirror/' + name)).join('\n')}
        }).call(host, host.CodeMirror, undefined, undefined, undefined);
        editorCodeMirror = host.CodeMirror;
        return editorCodeMirror;
    }
    const editorBaseCSS = ${JSON.stringify(css)};
`;
const source = read('src/plugin.js');
if (!source.includes('/* RYSM_VENDOR */')) throw Error('Missing vendor insertion point');
const output = '// Built by node blockbench/build.cjs; edit blockbench/src/plugin.js.\n' + source.replace('/* RYSM_VENDOR */', () => vendor);
const target = path.join(root, 'reimagined_ysm_physics_groups.js');
if (process.argv.includes('--check')) {
    if (fs.readFileSync(target, 'utf8') !== output) throw Error('Plugin bundle is stale');
} else fs.writeFileSync(target, output);
console.log('RYSM plugin bundle ' + crypto.createHash('sha256').update(output).digest('hex').slice(0, 12));
