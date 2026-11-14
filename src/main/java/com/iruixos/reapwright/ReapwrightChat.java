package com.iruixos.reapwright;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Consistent Reapwright chat prefix, independent of the message severity colour. */
public final class ReapwrightChat {
    private ReapwrightChat() { }

    // White brackets, light-green name, then reset before each message's own colour.
    public static final String PREFIX = "§f[§aReapwright§f]§r";

    public static Component prefix() {
        return Component.text("[", NamedTextColor.WHITE)
                .append(Component.text("Reapwright", NamedTextColor.GREEN))
                .append(Component.text("]", NamedTextColor.WHITE));
    }
}
