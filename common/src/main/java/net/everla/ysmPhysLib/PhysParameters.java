package net.everla.ysmPhysLib;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.function.ToDoubleFunction;

/** Units: seconds, blocks (gravity), degrees (angle), model pixels (radius). */
public record PhysParameters(float r, float g, Float gw, float s, float a, float c, float air, boolean bc,
                             List<String> disableAnimations, List<Condition> disableVariables,
                             List<String> enableAnimations, List<Condition> enableVariables) {
    public record Condition(String variable, String operator, float value) {
        public boolean test(ToDoubleFunction<String> values) {
            double actual=values.applyAsDouble(variable);
            return switch(operator) { case "=" -> actual==value; case ">=" -> actual>=value; case "<=" -> actual<=value;
                case ">" -> actual>value; case "<" -> actual<value; case "!=" -> actual!=value; default -> false; };
        }
    }
    /** Baking recipe identity, independent of the experimental mod release version. */
    public static final String GEOMETRY_CACHE_KEY = "rysmjs-group-declarations-8";
    public static final PhysParameters DEFAULT = new PhysParameters(1, 9.8f, null, 20, 90, 1, 0.5f, false,List.of(),List.of(),List.of(),List.of());
    public static final PhysParameters TAIL_DEFAULT = new PhysParameters(10, 0, 3f, 16, 50, 1, 0.5f, false,List.of(),List.of(),List.of(),List.of());

    public PhysParameters(float r, float g, float s, float a, float c, float air) {this(r,g,null,s,a,c,air,false,List.of(),List.of(),List.of(),List.of());}
    public boolean enabled(java.util.function.Predicate<String> animation, ToDoubleFunction<String> values) {
        boolean disable=disableAnimations.stream().anyMatch(animation)||!disableVariables.isEmpty()&&disableVariables.stream().allMatch(c->c.test(values));
        boolean hasEnable=!enableAnimations.isEmpty()||!enableVariables.isEmpty();
        boolean enable=!hasEnable||enableAnimations.stream().anyMatch(animation)||!enableVariables.isEmpty()&&enableVariables.stream().allMatch(c->c.test(values));
        return !disable&&enable;
    }

    public static boolean isEnd(String name) {
        return name.startsWith("ysmPhysEnd@") && name.length() > 11 && name.indexOf('@',11)<0;
    }

    public static boolean isCandidate(String name) {
        return name.startsWith("ysmTail") || name.startsWith("ysmPhys") && !name.startsWith("ysmPhysEnd") && !name.startsWith("ysmPhysC");
    }

    public static PhysParameters parse(String name, PhysParameters parent) {
        int at = name.indexOf('@');
        if (at < 0 || at == name.length() - 1 || name.indexOf('@', at + 1) >= 0)
            throw new IllegalArgumentException("expected ysmPhys[#parameters]@name");
        String prefix = name.substring(0, at);
        String kind=name.startsWith("ysmTail")?"ysmTail":"ysmPhys";
        if (prefix.equals(kind)) return parent;
        if (!prefix.startsWith(kind+"#") || prefix.length() == 8)
            throw new IllegalArgumentException("invalid physics prefix");
        float r = parent.r, g = parent.g, s = parent.s, a = parent.a, c = parent.c, air = parent.air;
        Float gw=parent.gw;
        boolean bc=parent.bc;
        var da=new ArrayList<>(parent.disableAnimations()); var dv=new ArrayList<>(parent.disableVariables());
        var ea=new ArrayList<>(parent.enableAnimations()); var ev=new ArrayList<>(parent.enableVariables());
        var seen = new HashSet<String>();
        for (String entry : splitEntries(prefix.substring(8))) {
            String raw=entry.trim();
            int eq=raw.indexOf('='); String[] pair=eq<0?new String[]{raw}:new String[]{raw.substring(0,eq),raw.substring(eq+1)};
            if (pair.length != 2 || !seen.add(pair[0].trim()))
                throw new IllegalArgumentException("invalid or duplicate parameter: " + entry);
            String key=pair[0].trim(); String valueText=pair[1].trim();
            if(key.equals("disable")||key.equals("enable")) {
                if(!valueText.startsWith("(")||!valueText.endsWith(")")) throw new IllegalArgumentException(key+" must use (...)");
                var names=key.equals("disable")?da:ea; var vars=key.equals("disable")?dv:ev;
                names.clear(); vars.clear();
                for(String item:valueText.substring(1,valueText.length()-1).split(",",-1)) { item=item.trim(); if(item.isEmpty()) throw new IllegalArgumentException("empty "+key+" entry");
                    var match=java.util.regex.Pattern.compile("^((?:v|variable|rysmjs)\\.[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*(>=|<=|!=|=|>|<)\\s*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)$").matcher(item);
                    if(match.matches()) {
                        float v=Float.parseFloat(match.group(3)); if(!Float.isFinite(v)) throw new IllegalArgumentException("non-finite condition");
                        vars.add(new Condition(match.group(1),match.group(2),v));
                    } else if(item.matches("[^\\s(),=<>!]+")) names.add(item);
                    else throw new IllegalArgumentException("invalid condition: "+item);
                }
                if(!names.isEmpty()&&!vars.isEmpty()) throw new IllegalArgumentException("use either animations or variable comparisons in "+key);
                continue;
            }
            if(key.equals("bc")) {
                String value=valueText;
                if(!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("bc must be true or false");
                bc=Boolean.parseBoolean(value);
                continue;
            }
            float value = Float.parseFloat(valueText);
            if (!Float.isFinite(value)) throw new IllegalArgumentException("non-finite parameter");
            switch (key) {
                case "r" -> r = value;
                case "g" -> { g = value; gw = null; }
                case "gw" -> { gw = value; g = 0; }
                case "s" -> s = value;
                case "a" -> a = value;
                case "c" -> c = value;
                case "air" -> air = value;
                default -> throw new IllegalArgumentException("unknown parameter: " + pair[0]);
            }
        }
        if (seen.contains("g") && seen.contains("gw"))
            throw new IllegalArgumentException("g and gw cannot coexist");
        if (r < 0 || s < 0 || c < 0 || air < 0 || a < 0 || a > 180)
            throw new IllegalArgumentException("parameter outside supported range");
        return new PhysParameters(r, g, gw, s, a, c, air, bc,List.copyOf(da),List.copyOf(dv),List.copyOf(ea),List.copyOf(ev));
    }

    private static List<String> splitEntries(String text) {
        List<String> result=new ArrayList<>(); int depth=0,start=0;
        for(int i=0;i<text.length();i++) { char c=text.charAt(i); if(c=='(') {if(++depth>1) throw new IllegalArgumentException("nested parentheses");} else if(c==')') {if(--depth<0) throw new IllegalArgumentException("unbalanced parentheses");} else if(c==','&&depth==0){result.add(text.substring(start,i));start=i+1;} }
        if(depth!=0) throw new IllegalArgumentException("unbalanced parentheses");
        result.add(text.substring(start)); return result;
    }
}
