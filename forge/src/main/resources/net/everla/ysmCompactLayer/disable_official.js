// Only official startup classes appear in this release-derived target list.
// Transforming the official Mixin plugin also prevents its constructor's compat side effects.
function initializeCoreMod() {
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var LdcInsnNode = Java.type('org.objectweb.asm.tree.LdcInsnNode');
    var MethodNode = Java.type('org.objectweb.asm.tree.MethodNode');
    var Type = Java.type('org.objectweb.asm.Type');
    var profiles = ASMAPI.loadData('rysm-official-forge.json');

    function empty(method, node) {
        if (node.superName !== 'java/lang/Object') throw new Error('Unexpected official YSM superclass: ' + node.name);
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables !== null) method.localVariables.clear();
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.access = method.access & ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT);
        if (method.name === '<init>') {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            var sort = Type.getReturnType(method.desc).getSort();
            if (sort === Type.VOID) method.instructions.add(new InsnNode(Opcodes.RETURN));
            else if (sort === Type.LONG) {
                method.instructions.add(new InsnNode(Opcodes.LCONST_0));
                method.instructions.add(new InsnNode(Opcodes.LRETURN));
            } else if (sort === Type.FLOAT) {
                method.instructions.add(new InsnNode(Opcodes.FCONST_0));
                method.instructions.add(new InsnNode(Opcodes.FRETURN));
            } else if (sort === Type.DOUBLE) {
                method.instructions.add(new InsnNode(Opcodes.DCONST_0));
                method.instructions.add(new InsnNode(Opcodes.DRETURN));
            } else if (sort === Type.ARRAY || sort === Type.OBJECT) {
                method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            } else {
                method.instructions.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
            }
        }
        method.maxStack = Math.max(method.maxStack, 2);
    }

    return {
        'disable_official_ysm': {
            'target': {
                'type': 'CLASS',
                'names': function () {
                    var names = {};
                    for (var i = 0; i < profiles.targets.length; i++) names['class' + i] = profiles.targets[i];
                    return names;
                }
            },
            'transformer': function (node) {
                var clinit = null;
                for (var i = 0; i < node.methods.size(); i++) {
                    var method = node.methods.get(i);
                    empty(method, node);
                    if (method.name === '<clinit>') clinit = method;
                }
                if (clinit === null) {
                    clinit = new MethodNode(Opcodes.ACC_STATIC, '<clinit>', '()V', null, null);
                    node.methods.add(clinit);
                }
                clinit.instructions.clear();
                clinit.instructions.add(new LdcInsnNode(node.name.replace(/\//g, '.')));
                clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    'net/everla/ysmCompactLayer/forge/ForgeOfficialDisabler', 'markDisabled', '(Ljava/lang/String;)V', false));
                clinit.instructions.add(new InsnNode(Opcodes.RETURN));
                clinit.maxStack = 1;
                return node;
            }
        }
    };
}
