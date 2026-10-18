package app.xodos2.ui.drawer.pages

object DesktopInstallScripts {

    /**
     * Builds a shell script that installs [envName].
     *
     * @param distro   distro id (used only for proot containers).
     * @param envName  human-readable desktop environment name.
     * @param isNative when true → Termux-style apt script using wildcards.
     *                 When false → original per-distro PRoot script.
     */
    fun buildDesktopInstallScript(
        distro: String,
        envName: String,
        isNative: Boolean = false
    ): String {
        if (isNative) return buildNativeInstallScript(envName)
        return buildDistroInstallScript(distro, envName)
    }

    // ──────────────────────────────────────────────────────────────
    // NATIVE XoDos-Ark (Termux-style) install — wildcard based
    // ──────────────────────────────────────────────────────────────
    private fun buildNativeInstallScript(envName: String): String {
        // Every DE in Termux's x11-repo is expressible as a wildcard prefix.
        // x11-repo ships no -dev / -debug split packages, so `apt install
        // 'prefix*'` is safe and concise.
        val packagePattern: String = when (envName) {
            "XFCE Desktop"   -> "xfce4*"
            "LXQt Desktop"   -> "lxqt*"
            "MATE"           -> "mate*"
            "Cinnamon"       -> "cinnamon*"
            "KDE Plasma"     -> "plasma*"
            "GNOME"          -> "gnome*"
            "Budgie"         -> "budgie*"
            "LXDE"           -> "lxde*"
            "Enlightenment"  -> "enlightenment*"
            "Openbox"        -> "openbox*"
            "i3"             -> "i3*"
            "Fluxbox"        -> "fluxbox*"
            "IceWM"          -> "icewm*"
            else             -> ""
        }

        if (packagePattern.isEmpty()) {
            return "echo 'Unknown desktop environment: $envName'; exit 1\n"
        }

        val prefix = "/data/data/app.xodos2/files/usr"
        val home   = "/data/data/app.xodos2/files/home"
        val sd     = "\u0024"   // literal shell dollar sign

        val body = """
            #!/data/data/app.xodos2/files/usr/bin/sh
            set -e

            echo "Installing $envName in native XoDos-Ark (Termux-style) environment..."

            export PREFIX="$prefix"
            export HOME="$home"
            export PATH="$prefix/bin:/system/bin:/system/xbin"
            export LD_LIBRARY_PATH="$prefix/lib"
            export PULSE_SERVER=127.0.0.1
            export DEBIAN_FRONTEND=noninteractive

            mkdir -p "$home"

            # Enable x11-repo (all desktop packages live there)
            apt install -y x11-repo || pkg install -y x11-repo
            apt update -y

            # Desktop environment (wildcard, safe because x11-repo has no -dev)
            apt install -y '$packagePattern'

            # Audio stack (same for every DE in Termux)
            apt install -y pulseaudio pavucontrol

            echo "Native $envName installation completed successfully."
        """.trimIndent()

        return "sh <<'EOF'\n$body\nEOF"
    }

    // ──────────────────────────────────────────────────────────────
    // DISTRO (PRoot) install — original implementation, unchanged
    // ──────────────────────────────────────────────────────────────
    private fun buildDistroInstallScript(distro: String, envName: String): String {
        val cleanDistro = distro.lowercase().trim()

        // Nix environment
        if (cleanDistro.contains("nix")) {
            return "source /nix/var/nix/profiles/default/etc/profile.d/nix.sh 2>/dev/null || true\n" +
                   "nix-channel --update && nix-env -u\n" +
                   "export PULSE_SERVER=127.0.0.1\n" +
                   "echo 'Nix profile packages updated successfully!'\n"
        }

        // Package manager & base deps – aggressive, force‑overwrite versions
        val (managerCmd, baseDeps) = when {
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
            cleanDistro.contains("kali") || cleanDistro.contains("trisquel") ->
                Pair(
                    "apt-get update -y && apt-get upgrade -y \n" +
                    "apt-get install -y --option Dpkg::Options::=\"--force-overwrite\"",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11"
                )
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") || cleanDistro.contains("artix") ->
                Pair(
                    "pacman -Syu --noconfirm \n" +
                    "pacman -S --noconfirm --overwrite \"*\"",
                    "mesa-utils xorg-server xorg-xwayland vulkan-devel mesa vulkan-tools  vulkan-virtio dbus"
                )
            cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") || cleanDistro.contains("rocky") ->
                Pair(
                    "dnf upgrade -y \n" +
                    "dnf install -y --allowerasing --skip-broken",
                    "glx-utils xorg-x11-server-Xwayland vulkan-loader-devel mesa-dri-drivers vulkan-tools dbus-x11"
                )
            cleanDistro.contains("alpine") ->
                Pair(
                    "apk update \n" +
                    "apk add ",
                    "mesa-utils xwayland mesa-egl mesa-gles vulkan-loader mesa-dri-gallium vulkan-tools dbus ttf-dejavu qt5-qtsvg qt6-qtsvg"
                )
            cleanDistro.contains("void") ->
                Pair(
                    "xbps-install -Syu \n" +
                    "xbps-install -y -f",
                    "mesa-utils xwayland vulkan-loader mesa-dri vulkan-tools dbus"
                )
            cleanDistro.contains("opensuse") ->
                Pair(
                    "zypper --non-interactive refresh \n" +
                    "zypper --non-interactive install ",
                    "mesa-utils xorg-x11-server-Xwayland vulkan-devel mesa-dri-drivers vulkan-tools dbus-1-x11"
                )
            else ->
                Pair(
                    "apt-get update -y && apt-get upgrade -y --allow-downgrades --allow-remove-essential || true\n" +
                    "apt-get install -y --option Dpkg::Options::=\"--force-overwrite\"",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11 pulseaudio"
                )
        }

        // ─── Kali‑specific setup (improved) ───
        val gpgSetup = if (cleanDistro.contains("kali")) {
            """

# ==========================================
# ADVANCED PROOT SYSTEMD BYPASSES
# ==========================================

echo "Creating container compatibility stubs..."

mkdir -p /etc
touch /etc/machine-id

for engine in /usr/bin/systemd-sysusers /usr/bin/systemd-tmpfiles /usr/sbin/dpkg-preconfigure /usr/sbin/pam-auth-update /usr/bin/linux-version /usr/sbin/update-initramfs /usr/share/debconf/frontend; do
    rm -f "${'$'}engine"
    echo '#!/bin/sh' > "${'$'}engine"
    echo 'exit 0' >> "${'$'}engine"
    chmod 777 "${'$'}engine"
done

echo "Neutralizing hardware package maintainer scripts..."
rm -f /var/lib/dpkg/info/systemd*.postinst
rm -f /var/lib/dpkg/info/udev*.postinst
rm -f /var/lib/dpkg/info/initramfs-tools*.postinst
rm -f /var/lib/dpkg/info/libpam*.postinst
rm -f /var/lib/dpkg/info/sudo*.postinst
rm -f /var/lib/dpkg/info/dbus*.postinst
rm -f /var/lib/dpkg/info/cron*.postinst
rm -f /var/lib/dpkg/info/dhcpcd*.postinst
rm -f /var/lib/dpkg/info/openssh*.postinst

export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
dpkg --configure -a --force-all

echo "deb http://kali.download/kali kali-rolling main non-free contrib non-free-firmware" > /etc/apt/sources.list
rm -rf /etc/apt/sources.list.d/
mkdir -p /etc/apt/sources.list.d/

apt-get update -y
apt-get install -y --no-install-recommends gnupg curl || true
curl -fsSL --connect-timeout 30 https://archive.kali.org/archive-key.asc | gpg --yes --dearmor -o /etc/apt/trusted.gpg.d/kali-archive-keyring.gpg || true

apt-get install -f -y --allow-downgrades
echo "Kali base environment aligned."
            """.trimIndent() + "\n"
        } else ""

        val libc6Setup = if (cleanDistro.contains("kali")) {
            """
                dpkg --configure -a --force-all
                apt-get install -f -y --allow-downgrades
                echo "Kali libc6 fixed."
            """.trimIndent() + "\n"
        } else ""

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
                cleanDistro.contains("alpine") -> "xfce4 xfce4-whiskermenu-plugin xfce4-taskmanager xfce4-pulseaudio-plugin xfce4-screenshooter xfce4-notifyd mousepad ristretto"
                cleanDistro.contains("manjaro") -> "xfce4 xfce4-goodies featherpad"
                cleanDistro.contains("kali") || cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "xfce4 xfce4-goodies xfce4-whiskermenu-plugin "
                cleanDistro.contains("fedora") -> "@xfce-desktop-environment qt5-qtbase-gui qt6-qtbase-gui"
                else -> "xfce4"
            }
            "LXQt Desktop" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "lxqt lxqt-themes featherpad"
                cleanDistro.contains("alpine") -> "lxqt-desktop lxqt-desktop openbox papirus-icon-theme"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") -> "lxqt openbox"
                cleanDistro.contains("fedora") -> "@lxqt-desktop-environment qt5-qtbase-gui qt6-qtbase-gui"
                else -> "lxqt"
            }
            "KDE Plasma" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "plasma-meta dolphin konsole plasma-x11-session kwin-x11"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") || cleanDistro.contains("kali") -> "kde-plasma-desktop"
                cleanDistro.contains("fedora") -> "@kde-desktop"
                else -> "plasma-desktop"
            }
            "GNOME" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "gnome gnome-tweaks"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") || cleanDistro.contains("kali") -> "gnome-core"
                cleanDistro.contains("fedora") -> "@gnome-desktop"
                else -> "gnome"
            }
            "MATE" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "mate mate-extra"
                cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") || cleanDistro.contains("kali") -> "mate-desktop-environment pcmanfm nitrogen"
                cleanDistro.contains("fedora") -> "@mate-desktop-environment qt5-qtbase-gui qt6-qtbase-gui"
                else -> "mate-desktop-environment"
            }
            "Cinnamon" -> when {
                cleanDistro.contains("arch") || cleanDistro.contains("manjaro") -> "cinnamon nemo"
                cleanDistro.contains("fedora") -> "@cinnamon-desktop-environment qt5-qtbase-gui qt6-qtbase-gui"
                else -> "cinnamon-desktop-environment"
            }
            else -> ""
        }

        val kdeArchProotFix = if (envName == "KDE Plasma" && (cleanDistro.contains("arch") || cleanDistro.contains("manjaro"))) {
            """
                echo "Downgrading kwin to fix PRoot Wayland crashing bug..."
                curl -L -o ~/kwin-6.6.5-4-aarch64.pkg.tar.xz https://github.com/xodiosx/XoDos-Ark/releases/download/4.18.1-1fix/kwin-6.6.5-4-aarch64.pkg.tar.xz
                pacman -U --noconfirm ~/kwin-6.6.5-4-aarch64.pkg.tar.xz
                balooctl6 status
                balooctl6 purge
                balooctl6 suspend
                balooctl6 disable
                echo "fixed in successfully."
            """.trimIndent() + "\n"
        } else ""

        var scriptBody = gpgSetup +
                "$managerCmd $desktopPackages $baseDeps $audioDeps\n" +
                kdeArchProotFix +
                "export PULSE_SERVER=127.0.0.1\n" +
                libc6Setup +
                "echo 'Installation completed!'\n"

        if (envName == "GNOME" &&
            (cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
             cleanDistro.contains("kali") || cleanDistro.contains("trisquel"))) {
            scriptBody += """
                rm -f /usr/share/dbus-1/system-services/org.freedesktop.login1.service
                echo 'fixed try using those commands to start desktop'
                rm -f /usr/share/dbus-1/system-services/org.freedesktop.login1.service

killall -9 gnome-session-binary dbus-daemon dbus-launch metacity gnome-panel 2>/dev/null
rm -f /run/dbus/pid && mkdir -p /run/dbus

export XDG_CURRENT_DESKTOP=GNOME
export DESKTOP_SESSION=gnome
export XDG_SESSION_DESKTOP=gnome
            """.trimIndent() + "\n"
        }

        if (envName == "MATE" &&
            (cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
             cleanDistro.contains("kali") || cleanDistro.contains("trisquel"))) {
            scriptBody += """
               mkdir -p ~/Desktop 
               chmod 755 ~/Desktop 
               mv /usr/bin/caja /usr/bin/caja-disabled
            """.trimIndent() + "\n"
        }

        if (envName == "LXQt Desktop" &&
            (cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
             cleanDistro.contains("kali") || cleanDistro.contains("alpine"))) {
            scriptBody += """
echo "Applying PRoot optimizations..."
            for file in /etc/xdg/autostart/*.desktop; do
                if [ -f "${'$'}file" ] && grep -qi "polkit" "${"$"}{file}"; then
                    rm -f "${'$'}file"
                fi
            done
            """.trimIndent() + "\n"
        }

        return "sh <<'EOF'\n${scriptBody}\nEOF"
    }
}