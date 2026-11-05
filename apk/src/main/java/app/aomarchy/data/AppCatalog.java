package app.aomarchy.data;

import java.util.ArrayList;
import java.util.List;

/**
 * The curated "common Linux apps" catalog shown on the console's Apps page: stable ids, the pacman command that
 * installs the package, and the T key names for its copy. Pure data — no rootfs reads, no network, no user-facing text
 * here: the UI resolves {@link Entry#labelKey}, {@link Entry#hintKey} and {@link Entry#groupKey} through {@code T}.
 *
 * <p>Every command is safe to type into a rootfs terminal as-is: {@code --needed} skips packages that are already
 * installed, so pressing install twice does not trigger pacman's reinstall prompt. pacman still asks for the usual
 * confirmation, which is expected: the user can press Enter in the terminal.
 *
 * <p>Entries are grouped contiguously (desktop, dev, media/tools) and keep that order inside a group, so the App page
 * can render section headings by comparing {@code groupKey} with the previous entry's.
 */
public final class AppCatalog {
    /** T keys for the section headings, in display order. */
    public static final String GROUP_DESKTOP = "catalog.group.desktop";
    public static final String GROUP_DEV = "catalog.group.dev";
    public static final String GROUP_MEDIA = "catalog.group.media";

    /** One catalog entry. All copy is a T key name; the command is already a complete pacman command line. */
    public static final class Entry {
        /** Stable lowercase id; also the suffix of {@link #labelKey} and {@link #hintKey}. */
        public final String id;
        /** T key of the app name, e.g. "catalog.firefox". */
        public final String labelKey;
        /** T key of the one-line description, e.g. "catalog.hint.firefox". */
        public final String hintKey;
        /** Complete command typed into the terminal, e.g. "sudo pacman -S --needed firefox". */
        public final String command;
        /** T key of the section heading, one of the GROUP_* constants. */
        public final String groupKey;

        private Entry(String id, String groupKey, String command) {
            this.id = id;
            this.groupKey = groupKey;
            this.command = command;
            this.labelKey = "catalog." + id;
            this.hintKey = "catalog.hint." + id;
        }
    }

    private AppCatalog() {
    }

    /** The whole catalog in display order. A fresh list every call, so callers may sort/filter it freely. */
    public static List<Entry> all() {
        List<Entry> entries = new ArrayList<>();

        // Desktop essentials.
        entries.add(new Entry("firefox", GROUP_DESKTOP, "sudo pacman -S --needed firefox"));
        entries.add(new Entry("kitty", GROUP_DESKTOP, "sudo pacman -S --needed kitty"));
        entries.add(new Entry("waybar", GROUP_DESKTOP, "sudo pacman -S --needed waybar"));
        entries.add(new Entry("nautilus", GROUP_DESKTOP, "sudo pacman -S --needed nautilus"));
        entries.add(new Entry("code", GROUP_DESKTOP, "sudo pacman -S --needed code"));
        entries.add(new Entry("fastfetch", GROUP_DESKTOP, "sudo pacman -S --needed fastfetch"));

        // Development.
        entries.add(new Entry("neovim", GROUP_DEV, "sudo pacman -S --needed neovim"));
        entries.add(new Entry("git", GROUP_DEV, "sudo pacman -S --needed git"));
        entries.add(new Entry("python", GROUP_DEV, "sudo pacman -S --needed python"));
        entries.add(new Entry("node", GROUP_DEV, "sudo pacman -S --needed nodejs npm"));

        // Media and small tools.
        entries.add(new Entry("mpv", GROUP_MEDIA, "sudo pacman -S --needed mpv"));
        entries.add(new Entry("gimp", GROUP_MEDIA, "sudo pacman -S --needed gimp"));
        entries.add(new Entry("btop", GROUP_MEDIA, "sudo pacman -S --needed btop"));
        entries.add(new Entry("p7zip", GROUP_MEDIA, "sudo pacman -S --needed p7zip"));

        return entries;
    }
}
