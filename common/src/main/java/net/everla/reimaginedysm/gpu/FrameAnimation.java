package net.everla.reimaginedysm.gpu;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Vertical UV strip; frame zero is the authored UV rectangle. */
public record FrameAnimation(double tick, int count, String macro, boolean macroRepeat) {
    public static FrameAnimation parse(String name) {
        if (!name.startsWith("FrameAnimation")) return null;
        String prefix = name.split("@", -1)[0];
        if (!prefix.startsWith("FrameAnimation#") || name.chars().filter(c -> c == '@').count() > 1
                || name.endsWith("@"))
            throw new IllegalArgumentException("expected FrameAnimation#count=N,tick=T@name or count=N,macro=v.path@name");
        Map<String, String> parameters = new HashMap<>();
        for (String entry : prefix.substring("FrameAnimation#".length()).split(",", -1)) {
            String[] pair = entry.trim().split("=", -1);
            if (pair.length != 2 || !java.util.Set.of("tick", "count", "macro", "macroRepeat").contains(pair[0].trim())
                    || pair[1].isBlank() || parameters.putIfAbsent(pair[0].trim(), pair[1].trim()) != null)
                throw new IllegalArgumentException("invalid or duplicate frame parameter: " + entry);
        }
        int count = Integer.parseInt(parameters.getOrDefault("count", "0"));
        if (count < 1) throw new IllegalArgumentException("count must be a positive integer");
        String macro = parameters.get("macro");
        if (parameters.containsKey("tick") == (macro != null))
            throw new IllegalArgumentException("specify exactly one of tick or macro");
        double tick = macro == null ? Double.parseDouble(parameters.get("tick")) : 0;
        if (macro == null && (!Double.isFinite(tick) || tick <= 0))
            throw new IllegalArgumentException("tick must be finite and positive (Minecraft ticks)");
        if (macro != null && !macro.matches("(?:v|variable)\\.[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))
            throw new IllegalArgumentException("macro must be a v.* or variable.* variable path");
        String repeat = parameters.getOrDefault("macroRepeat", "true");
        if (!repeat.equals("true") && !repeat.equals("false"))
            throw new IllegalArgumentException("macroRepeat must be true or false");
        return new FrameAnimation(tick, count, macro, Boolean.parseBoolean(repeat));
    }

    public int frame(double ticks, ToDoubleFunction<String> variables) {
        double value = macro == null ? Math.floor(ticks / tick) : variables.applyAsDouble(macro);
        if (!Double.isFinite(value)) return 0;
        if (macro == null) return (int) Math.floorMod((long) value, (long) count);
        long frame = Math.max(1, Math.round(value));
        return macroRepeat ? (int) ((frame - 1) % count) : (int) Math.min(frame - 1, count - 1);
    }
}
