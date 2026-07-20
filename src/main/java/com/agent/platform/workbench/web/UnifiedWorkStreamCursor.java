package com.agent.platform.workbench.web;

public record UnifiedWorkStreamCursor(long workSequence, long primaryRunSequence) {

    public String encode() {
        return "w:" + workSequence + ";r:" + primaryRunSequence;
    }

    public static UnifiedWorkStreamCursor resolve(long workSequence,
                                                  long primaryRunSequence,
                                                  String lastEventId) {
        UnifiedWorkStreamCursor header = parse(lastEventId);
        return new UnifiedWorkStreamCursor(
                Math.max(workSequence, header.workSequence()),
                Math.max(primaryRunSequence, header.primaryRunSequence()));
    }

    public static UnifiedWorkStreamCursor parse(String value) {
        long work = -1;
        long run = -1;
        if (value != null) {
            for (String part : value.trim().split(";")) {
                String[] pair = part.split(":", 2);
                if (pair.length != 2) continue;
                try {
                    if ("w".equals(pair[0])) work = Long.parseLong(pair[1]);
                    if ("r".equals(pair[0])) run = Long.parseLong(pair[1]);
                } catch (NumberFormatException ignored) { }
            }
        }
        return new UnifiedWorkStreamCursor(Math.max(-1, work), Math.max(-1, run));
    }
}
