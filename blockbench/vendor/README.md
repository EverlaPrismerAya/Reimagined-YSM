# Offline editor dependencies

The plugin bundles CodeMirror **5.65.16** and Acorn **8.14.1**, both under the MIT license. Upstream files are unmodified; their license files are included in each package directory. Downloads came from the corresponding versioned npm package tarballs.

Run `node blockbench/build.cjs` after changing `src/plugin.js` or these dependencies. The resulting `reimagined_ysm_physics_groups.js` contains everything needed at runtime; users load only that file. Dependency loading does not replace Blockbench's own CodeMirror instance.
