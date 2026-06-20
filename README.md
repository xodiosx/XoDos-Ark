# XoDos-Ark 
[العربية](README.ar.md) | English
**XoDos-Ark: A ship small enough to fit in your pocket, but large enough to house every world you've ever imagined**
# XoDos‑Ark – The Digital Ark for Everything That Runs

**"One Ship. Every System. No Limits."**

XoDos‑Ark is a native Android application that wraps the powerful [XoDos](https://github.com/xodiosx/XoDos) and [XoDos:Re](https://github.com/xodiosx/XoDos2) emulator inside a beautiful, secure container. It preserves your entire digital legacy—operating systems, applications, and games—no matter how old or obscure. Think of it as **Noah’s Ark for software**: every byte of your history survives the flood of obsolescence.

<p align="center">
  <!-- Replace with your actual hosted logo image -->
  <img src="assets/logo.png" alt="XoDos-Ark Logo" width="256"/>
</p>

---

## 🌊 The Flood of Time Can’t Drown What’s Inside the Ark

Our mission:

> *"In the deluge of digital obsolescence, every byte of your legacy deserves a place on the ship."*

XoDos‑Ark isn’t just an emulator—it’s a **vessel of imagination**. It stops your favourite vintage OS, abandoned software, and childhood games from being erased by hardware evolution. Whether you need a Windows XP desktop, a Linux playground, or a DOS game library, this single app carries them all.

> *"Bounded in a nutshell, yet king of infinite space—XoDos‑Ark is the container for every digital dream."*  
> — inspired by *Hamlet*, Act II, Scene II

---

## ✨ Why XoDos‑Ark?

- **All‑in‑One Vessel**  
  Run Windows, Linux, DOS, and exotic retro systems simultaneously—no separate VMs required.  
  *“Two of every kind? No—XoDos‑Ark holds **every** kind.”*

- **Pocket‑Sized Infinity**  
  The entire history of personal computing fits on your phone. Save snapshots, share configurations, and never lose a program again.  
  *“A ship small enough to fit in your pocket, but large enough to house every world you’ve ever imagined.”*

- **Built for Android**  
  Optimised touch controls, and seamless file sharing between guest and host. Emulation doesn’t get more portable than this.

- **Preservation First**  
  XoDos‑Ark is not about running the latest software—it’s about keeping the old ones alive. Every version you archive is a win against digital entropy.  
  *“The Ark isn’t just for survival; it’s for the rebirth of every program you thought was lost to the tide.”*

- **Secure by Design**  
  Each guest runs inside an isolated sandbox. Your host data stays protected, even when you experiment with unstable or outdated OS images.

---

## 🚢 Taglines That Capture Our Vision

 *XoDos‑Ark: Where the world sees a flood, we see a cargo manifest.*
 *Weather the digital storm. Sail with XoDos‑Ark.*                 
---

## 📸 Screenshots

| Home Screen | Running Windows XP | Running Linux |
|-------------|-------------------|---------------|
| ![Home](screenshots/home.png) | ![Windows](screenshots/winxp.png) | ![Linux](screenshots/linux.png) |

---

## 🚀 Getting Started

---

## ⁉️ How to Install and Basic Start Usage

1. Download the latest APK version from the [Releases](https://github.com/xodiosx/XoDos-Ark/releases) page.
2. Install the APK and grant all necessary permissions when prompted.
3. **(Requires an internet connection)** Wait a moment for the list of available Linux distributions to appear. Select your preferred distro and wait for the download and installation process to complete. Once finished, you will be prompted to restart—go ahead and restart to log in directly to the distro shell.

---

Once logged into the shell, follow these steps to set up your environment:

**1. System Update**  
Always start by updating and upgrading all system packages to the latest versions:
```bash
apt update && apt upgrade -y
```

2. (Optional) Create a Non-Root User
Some applications require a dedicated user instead of the default root user. To create one, install the necessary tools and set up a new user with sudo privileges:

```bash
apt install sudo adduser -y
adduser xodos
# Follow the prompts to set a password and user details
usermod -aG sudo xodos
```

Now you can switch to the new user with su - xodos for better security. For simplicity, this guide continues using the root user.

3. Install a Desktop Environment
Tap the floating ball icon to open the drawer, scroll down to the "Install Desktop Environment" option, and choose your preferred GUI—for example, XFCE4. This will install all the essential packages automatically.

4. Configure the Display Backend
After installation, you need to set up the display backend. You have two options: Wayland and X11. Since most desktop environments (including XFCE4) support X11, we will select it first.

· Once you choose X11, a black screen may appear briefly. Press the back button, then from the X11 drawer select "Exit". At this point, X11 will be running in the background and ready to use.

5. Start the Desktop Environment
You have two ways to launch the DE:

· Via terminal – by typing the commands directly.
· Via script button – using the X11 or Wayland script inside the floating ball drawer.

For simplicity, we will use the terminal directly. Example for starting XFCE4:

```bash
export DISPLAY=:0
dbus-launch --exit-with-session xfce4-session &
```

### 📦 Download (Pre‑built APK)

Go to [Releases](https://github.com/xodiosx/XoDos-Ark/releases) and grab the latest stable APK.  
Minimum Android version: **10 (API 29)**. The ark sails on even older devices.

### 🔨 help Building the Ark from Source

1. **Clone the repository**
   ```bash
   git clone https://github.com/xodiosx/XoDos-Ark.git
   cd XoDos-Ark
```

## 🙏 Acknowledgments

XoDos‑Ark stands on the shoulders of giants:

- [Termux](https://github.com/termux/termux-app) – the terminal emulator and Linux environment for Android  
- [Termux-X11](https://github.com/termux/termux-x11) – a Termux add-on that runs X11 applications natively  
- [Wayland](https://wayland.freedesktop.org/) – the modern display protocol enabling seamless graphical integration  
- [Trierarch](https://github.com/Beauty114514/trierarch/) – an inspiring open-source vessel