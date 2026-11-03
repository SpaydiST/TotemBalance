package org.example1.totemBalance;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

public final class ProgressionAdvancement {

    private static final String NAMESPACE = "server_progression";
    private static final String DEFAULT_ACHIEVEMENT = "totem_balance";

    private ProgressionAdvancement() {
    }

    public static void award(Player player) {
        award(player, DEFAULT_ACHIEVEMENT);
    }

    public static void award(Player player, String achievement) {
        if (player == null || !player.isOnline() || achievement == null || achievement.isBlank()) {
            return;
        }
        if (!DEFAULT_ACHIEVEMENT.equals(achievement)) {
            awardOne(player, DEFAULT_ACHIEVEMENT);
        }
        awardOne(player, achievement);
    }

    private static void awardOne(Player player, String achievement) {
        Advancement advancement = Bukkit.getAdvancement(new NamespacedKey(NAMESPACE, achievement));
        if (advancement == null) {
            return;
        }

        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (!progress.isDone() && progress.getRemainingCriteria().contains("complete")) {
            progress.awardCriteria("complete");
        }
    }
}
