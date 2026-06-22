package app.xodos2.ui.drawer.pages

object DesktopInstallScripts {

    fun buildDesktopInstallScript(distro: String, envName: String): String {
        val cleanDistro = distro.lowercase().trim()

        // Nix environment handling override
        // Since Nix profiles can't manage system-level desktop environments or global drivers imperatively,
        // we just update the channels and upgrade any existing user-profile packages.
        if (cleanDistro.contains("nix")) {
            return "source /nix/var/nix/profiles/default/etc/profile.d/nix.sh 2>/dev/null || true\n" +
                   "nix-channel --update && nix-env -u\n" +
                   "export PULSE_SERVER=127.0.0.1\n" +
                   "echo 'Nix profile packages updated successfully!'\n"
        }

        val (managerCmd, baseDeps) = when {
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
            cleanDistro.contains("kali") || cleanDistro.contains("trisquel") ->
                Pair("apt update && apt upgrade -y && apt install -y",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11")
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") || cleanDistro.contains("artix") ->
                Pair("pacman -Syu --noconfirm --needed",
                    "mesa-utils xorg-xwayland vulkan-devel mesa vulkan-tools dbus")
            cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") || cleanDistro.contains("rocky") ->
                Pair("dnf upgrade -y && dnf install -y",
                    "mesa-utils xorg-x11-server-Xwayland vulkan-loader-devel mesa-dri-drivers vulkan-tools dbus-x11")
            cleanDistro.contains("alpine") ->
                Pair("apk update && apk add",
                    "mesa-utils xwayland vulkan-loader mesa-dri-gallium vulkan-tools dbus")
            cleanDistro.contains("void") ->
                Pair("xbps-install -Su && xbps-install -y",
                    "mesa-utils xwayland vulkan-loader mesa-dri vulkan-tools dbus")
            cleanDistro.contains("opensuse") ->
                Pair("zypper --non-interactive refresh && zypper --non-interactive install ",
                    "mesa-utils xorg-x11-server-Xwayland vulkan-devel mesa-dri-drivers vulkan-tools dbus-1-x11")
            else ->
                Pair("apt update && apt upgrade -y && apt install -y",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11")
        }

        val isModernDE = envName == "GNOME" || envName == "KDE Plasma"
        val audioDeps = if (isModernDE) {
            when {
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") || cleanDistro.contains("kali") ->
                    "pipewire pipewire-pulse wireplumber pavucontrol"
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") ->
                    "pipewire-pulse wireplumber pavucontrol"
                cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") ->
                    "pipewire-pulseaudio wireplumber pavucontrol"
                cleanDistro.contains("alpine") ->
                    "pipewire pipewire-pulse wireplumber pavucontrol"
                cleanDistro.contains("void") ->
                    "pipewire wireplumber pavucontrol"
                cleanDistro.contains("opensuse") ->
                    "pipewire-pulseaudio wireplumber pavucontrol"
                else -> "pipewire pipewire-pulse pavucontrol"
            }
        } else {
            "pulseaudio pavucontrol"
        }

        val desktopPackages = when (envName) {
            "XFCE Desktop" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "xfce4 xfce4-goodies"
                cleanDistro.contains("alpine") -> "xfce4 xfce4-terminal"
                else -> "xfce4 xfce4-goodies"
            }
            "LXQt Desktop" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "lxqt lxqt-themes featherpad"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "lxqt openbox"
                else -> "lxqt"
            }
            "KDE Plasma" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "plasma-desktop kde-applications"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "kde-plasma-desktop"
                cleanDistro.contains("fedora") -> "@kde-desktop"
                else -> "plasma-desktop"
            }
            "GNOME" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "gnome gnome-tweaks"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "gnome-core"
                cleanDistro.contains("fedora") -> "@gnome-desktop"
                else -> "gnome"
            }
            "MATE" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "mate mate-extra"
                else -> "mate-desktop-environment"
            }
            "Cinnamon" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "cinnamon nemo"
                else -> "cinnamon-desktop-environment"
            }
            else -> ""
        }

        return "$managerCmd $desktopPackages $baseDeps $audioDeps\n" +
               "export PULSE_SERVER=127.0.0.1\n" +
               "echo 'Installation completed!'\n"
    }
}
