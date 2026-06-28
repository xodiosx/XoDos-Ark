package app.xodos2.ui.drawer.pages

object DesktopInstallScripts {

    fun buildDesktopInstallScript(distro: String, envName: String): String {
        val cleanDistro = distro.lowercase().trim()

        // Nix environment
        if (cleanDistro.contains("nix")) {
            return "source /nix/var/nix/profiles/default/etc/profile.d/nix.sh 2>/dev/null || true\n" +
                   "nix-channel --update && nix-env -u\n" +
                   "export PULSE_SERVER=127.0.0.1\n" +
                   "echo 'Nix profile packages updated successfully!'\n"
        }

        // Package manager & base deps
        val (managerCmd, baseDeps) = when {
            cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
            cleanDistro.contains("kali") || cleanDistro.contains("trisquel") ->
                Pair("apt update && apt upgrade -y && apt install -y",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11 zip unzip")
            cleanDistro.contains("arch") || cleanDistro.contains("manjaro") || cleanDistro.contains("artix") ->
                Pair("pacman -Syu --noconfirm --needed",
                    "mesa-utils xorg-xwayland vulkan-devel mesa vulkan-tools dbus")
            cleanDistro.contains("fedora") || cleanDistro.contains("almalinux") || cleanDistro.contains("rocky") ->
                Pair("dnf upgrade -y && dnf install -y",
                    "mesa-utils xorg-x11-server-Xwayland vulkan-loader-devel mesa-dri-drivers vulkan-tools dbus-x11 zip unzip")
            cleanDistro.contains("alpine") ->
                Pair("apk update && apk add",
                    "mesa-utils xwayland vulkan-loader mesa-dri-gallium vulkan-tools dbus")
            cleanDistro.contains("void") ->
                Pair("xbps-install -Su && xbps-install -y",
                    "mesa-utils xwayland vulkan-loader mesa-dri vulkan-tools dbus")
            cleanDistro.contains("opensuse") ->
                Pair("zypper --non-interactive refresh && zypper --non-interactive install ",
                    "mesa-utils xorg-x11-server-Xwayland vulkan-devel mesa-dri-drivers vulkan-tools dbus-1-x11 zip unzip")
            else ->
                Pair("apt update && apt upgrade -y && apt install -y",
                    "mesa-utils xwayland libvulkan-dev mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 vulkan-tools dbus-x11 zip unzip")
        }

       // ─── Kali‑specific setup (improved) ───
        // ─── Kali‑specific setup (improved) ───
        val gpgSetup = if (cleanDistro.contains("kali")) {
            """

# ==========================================
# ADVANCED PROOT SYSTEMD BYPASSES
# ==========================================

echo "Creating container compatibility stubs..."

# 1. Provide an empty machine-id file if missing
mkdir -p /etc
touch /etc/machine-id

# 2. HARD-STUB the failing configuration engines 
# This handles systemd 260+ sysusers and tmpfiles verification errors
for engine in /usr/bin/systemd-sysusers /usr/bin/systemd-tmpfiles /usr/sbin/dpkg-preconfigure /usr/sbin/pam-auth-update /usr/bin/linux-version /usr/sbin/update-initramfs /usr/share/debconf/frontend; do
    rm -f "${'$'}engine"
    echo '#!/bin/sh' > "${'$'}engine"
    echo 'exit 0' >> "${'$'}engine"
    chmod 777 "${'$'}engine"
done

# 3. Purge existing post-install files causing dependency log jams
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

# Force dpkg to mark the previously failing packages as configured
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
dpkg --configure -a --force-all

# 4. Set clean software repository targets
echo "deb http://kali.download/kali kali-rolling main non-free contrib non-free-firmware" > /etc/apt/sources.list
rm -rf /etc/apt/sources.list.d/
mkdir -p /etc/apt/sources.list.d/

apt-get update -y
apt-get install -y --no-install-recommends gnupg curl || true
curl -fsSL --connect-timeout 30 https://archive.kali.org/archive-key.asc | gpg --yes --dearmor -o /etc/apt/trusted.gpg.d/kali-archive-keyring.gpg || true

# Force dependency fixes clear before pulling down target desktop environments
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

        // Audio dependencies
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

        // Desktop environment packages
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

        // Build script body
        var scriptBody = gpgSetup +
                "$managerCmd $desktopPackages $baseDeps $audioDeps\n" +
                "export PULSE_SERVER=127.0.0.1\n" +
                libc6Setup +
                "echo 'Installation completed!'\n"

        // XFCE fix for Debian‑based
        if (envName == "XFCE Desktop" &&
            (cleanDistro.contains("debian") || cleanDistro.contains("ubuntu") ||
             cleanDistro.contains("kali") || cleanDistro.contains("trisquel"))) {
            scriptBody += """
                # Apply xfce4-fix
                echo 'no need x11 fixed '
                
            """.trimIndent() + "\n"
        }

        // Always wrap in a heredoc
        return "sh <<'EOF'\n${scriptBody}\nEOF"
    }
}