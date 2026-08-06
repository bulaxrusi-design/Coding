package com.ursafe.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class QaGameCatalog {
    private QaGameCatalog() {}

    public static List<Game> list(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL);
        List<Game> games = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (packageName == null || packageName.isEmpty()) continue;
            if (packageName.equals(context.getPackageName()) || !seen.add(packageName)) continue;
            CharSequence rawLabel = info.loadLabel(pm);
            String label = rawLabel == null ? packageName : rawLabel.toString();
            games.add(new Game(label, packageName));
        }
        Collections.sort(games, Comparator.comparing(game -> game.label.toLowerCase()));
        return games;
    }

    public static final class Game {
        public final String label;
        public final String packageName;

        public Game(String label, String packageName) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
        }

        @Override public String toString() {
            return label + "\n" + packageName;
        }
    }
}
