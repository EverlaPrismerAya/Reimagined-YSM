package net.everla.ysmJsLib;

import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;
import java.nio.file.*;
import java.util.*;

/** Rewrites registration calls by source range, preserving unrelated script text. */
public final class ScriptSyntaxMigration {
    private ScriptSyntaxMigration() {}
    private record Edit(int start,int end,String text) {}
    public static String convert(String source) {
        var env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);
        var edits=new ArrayList<Edit>();
        String candidate="__rysm_event";
        while(source.contains(candidate))candidate+="_";
        final String event=candidate;
        var tree=new Parser(env).parse(source,"migration",1);
        tree.visit(node->{
            if(node instanceof FunctionCall call && call.getTarget().toSource().equals("Rysm.logKeyBindings") && call.getEnclosingFunction()!=null)
                edits.add(new Edit(call.getAbsolutePosition(),call.getAbsolutePosition()+call.getLength(),"void 0"));
            return true;
        });
        if(!edits.isEmpty()) {
            var result=new StringBuilder(source);edits.sort(Comparator.comparingInt(Edit::start).reversed());
            for(var edit:edits)result.replace(edit.start,edit.end,edit.text);
            return convert(result.toString());
        }
        tree.visit(node->{
            if(!(node instanceof FunctionCall call))return true;
            String target=call.getTarget().toSource();var args=call.getArguments();
            if(!target.equals("Rysm.group")) {
                String type=switch(target) {
                    case "Rysm.molang" -> "molang";
                    case "Rysm.cosmic" -> "cosmic";
                    case "Rysm.update" -> "tick";
                    case "Rysm.pose" -> "pose";
                    case "Rysm.logKeyBindings" -> "load";
                    default -> null;
                };
                if(type==null)return true;
                String replacement;
                if(type.equals("tick") || type.equals("pose")) {
                    if(args.size()!=1)throw new IllegalArgumentException("Expected one "+type+" callback");
                    replacement="Rysm.model('"+type+"', "+text(source,args.get(0))+")";
                }else {
                    if(type.equals("load")&&!args.isEmpty())throw new IllegalArgumentException("Expected no logKeyBindings arguments");
                    String arguments=source.substring(call.getLp()+call.getAbsolutePosition()+1,call.getRp()+call.getAbsolutePosition());
                    replacement="Rysm.model('"+type+"', "+event+" => "+event+"."+(type.equals("load")?"logKeyBindings":"register")+"("+arguments+"))";
                }
                edits.add(new Edit(call.getAbsolutePosition(),call.getAbsolutePosition()+call.getLength(),replacement));return false;
            }
            if(args.size()!=2)throw new IllegalArgumentException("Expected group name and properties");
            StringBuilder replacement=new StringBuilder("Rysm.model('group_modifier', ").append(event).append(" => ").append(event).append(".modify(").append(text(source,args.get(0)));
            if(args.get(1) instanceof ObjectLiteral properties && properties.getElements().stream().allMatch(p->p.getType()==org.mozilla.javascript.Token.COLON && key(p).matches("physics|end|collider|glow|item|facingCamera|cosmic|outline|frameAnimation"))) {
                replacement.append(")");
                for(var property:properties.getElements())replacement.append(".").append(key(property)).append("(").append(text(source,property.getRight())).append(")");
            }else replacement.append(", ").append(text(source,args.get(1))).append(")");
            replacement.append(")");
            edits.add(new Edit(call.getAbsolutePosition(),call.getAbsolutePosition()+call.getLength(),replacement.toString()));
            return false;
        });
        var result=new StringBuilder(source);
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for(var edit:edits)result.replace(edit.start,edit.end,edit.text);
        return result.toString();
    }
    private static String key(ObjectProperty p) {return p.getLeft() instanceof StringLiteral s?s.getValue():p.getLeft().toSource();}
    private static String text(String source,AstNode node) {return source.substring(node.getAbsolutePosition(),node.getAbsolutePosition()+node.getLength());}
}
