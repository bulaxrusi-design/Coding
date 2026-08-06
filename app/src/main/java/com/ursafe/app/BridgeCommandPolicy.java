package com.ursafe.app;

import java.util.regex.Pattern;

public final class BridgeCommandPolicy {
    public static final int MAX_COMMAND_LENGTH = 8000;
    private static final Pattern[] BLOCKED = new Pattern[]{
            Pattern.compile("(^|[;&|]\\s*)(su|sudo)(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brm\\s+[^\\n]*-[^\\n]*r[^\\n]*f[^\\n]*\\s+/(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(mkfs|mkswap)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\b[^\\n]*\\bof=/dev/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(reboot|shutdown|poweroff|halt)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsetenforce\\s+0\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(curl|wget)\\b[^\\n|]*\\|\\s*(sh|bash)\\b", Pattern.CASE_INSENSITIVE)
    };

    private BridgeCommandPolicy() {}

    public static String rejectionReason(String command) {
        if (command == null || command.trim().isEmpty()) return "ბრძანება ცარიელია.";
        if (command.length() > MAX_COMMAND_LENGTH) return "ბრძანება ზედმეტად გრძელია.";
        for (Pattern pattern : BLOCKED) {
            if (pattern.matcher(command).find()) return "Ursafe-მა მაღალი რისკის ბრძანება ამოიცნო.";
        }
        return null;
    }
}
