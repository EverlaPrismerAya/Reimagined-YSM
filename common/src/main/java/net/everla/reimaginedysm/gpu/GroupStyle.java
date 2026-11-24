package net.everla.reimaginedysm.gpu;

import java.util.HashSet;

/** Group-name syntax follows ysmPhys: prefix#key=value,key=value@name. */
public record GroupStyle(boolean cosmic, int spaceColor, float spaceAlpha, int astralColor,
                         float astralAlpha, boolean outline, int outlineColor, float outlineWidth) {
    public static final GroupStyle NONE = new GroupStyle(false, -1, 0, -1, 0, false, -1, 2);

    public static GroupStyle parse(String name, GroupStyle parent) {
        boolean cosmic = name.startsWith("ysmCosmic"), outline = name.startsWith("ysmOutline");
        if (!cosmic && !outline) return parent;
        String kind = cosmic ? "ysmCosmic" : "ysmOutline";
        int at = name.indexOf('@');
        String prefix = at < 0 ? name : name.substring(0, at);
        if (at >= 0 && (at == name.length()-1 || name.indexOf('@', at+1) >= 0))
            throw new IllegalArgumentException("expected " + kind + "[#parameters]@name");
        if (!prefix.equals(kind) && !prefix.startsWith(kind + "#"))
            throw new IllegalArgumentException("invalid group prefix: " + prefix);
        int sc = cosmic ? -1 : parent.spaceColor, ac = cosmic ? -1 : parent.astralColor;
        float sa = cosmic ? 0 : parent.spaceAlpha, aa = cosmic ? 0 : parent.astralAlpha;
        int color = outline ? -1 : parent.outlineColor;
        float width = outline ? 2 : parent.outlineWidth;
        var seen = new HashSet<String>();
        if (prefix.length() > kind.length()) {
            for (String entry : prefix.substring(kind.length()+1).split(",", -1)) {
                String[] pair = entry.trim().split("=", -1);
                if (pair.length != 2 || !seen.add(pair[0].trim()))
                    throw new IllegalArgumentException("invalid or duplicate parameter: " + entry);
                String key=pair[0].trim(), value=pair[1].trim();
                if (cosmic) switch (key) {
                    case "sc" -> sc = rgb(value);
                    case "sa" -> sa = alpha(value);
                    case "ac" -> ac = rgb(value);
                    case "aa" -> aa = alpha(value);
                    default -> throw new IllegalArgumentException("unknown cosmic parameter: " + key);
                }
                else if (key.equals("color")) color=value.equals("auto") ? -1 : rgb(value);
                else if (key.equals("width")) {
                    width=Float.parseFloat(value);
                    if(!Float.isFinite(width) || width<0 || width>64)
                        throw new IllegalArgumentException("outline width must be 0..64 pixels");
                }
                else throw new IllegalArgumentException("unknown outline parameter: " + key);
            }
        }
        return new GroupStyle(cosmic || parent.cosmic, sc, sa, ac, aa, outline || parent.outline, color, width);
    }

    private static int rgb(String text) {
        if (!text.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("RGB requires six hex digits");
        return Integer.parseInt(text,16);
    }
    private static float alpha(String text) {
        float n=Float.parseFloat(text);
        if (!Float.isFinite(n) || n<0 || n>100) throw new IllegalArgumentException("alpha must be 0..100 percent");
        return n/100;
    }
}
