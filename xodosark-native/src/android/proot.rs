//! proot.rs
//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.
//!
//! A rootfs is considered **proot‑compatible** only when **all** of these exist:
//! * `.xodos2_rootfs_ok` sentinel
//! * `etc/os-release` or ( `usr/bin` and `root` ) – broad compatibility
//! * `sys/.empty` directory
//! If any of them is missing, the container is treated as non‑proot and a
//! lightweight Android shell is launched with `PREFIX` pointing to its `/usr`.
//!
//! ## Per‑container options (username / hostname / shared home / kernel)
//!
//! The Kotlin side writes `<filesDir>/proot_options/container_<id>.json`:
//!
//! ```json
//! {
//!   "username": "user",
//!   "hostname": "localhost",
//!   "shared_home": false,
//!   "shared_tmp": true,
//!   "kernel_release": ""
//! }
//! ```
//!
//! `fork_pty_shell_in_rootfs()` reads this file directly (container id is
//! derived from the rootfs path) so **no JNI signature changes** are required.
//! `shared_tmp` is always forced true by the argv builder.
use super::{get_application_context, has_rootfs};
use super::{host_pulse_runtime_dir, guest_pulse_server_env, GUEST_PULSE_RUNTIME_MOUNT};
use anyhow::{Context, Result};
use nix::pty::{forkpty, ForkptyResult, Winsize};
use nix::unistd::{dup, execve, Pid};
use std::ffi::{CStr, CString};
use std::fs::{self, File};
use std::io::Write;
use std::os::fd::IntoRawFd;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::{FromRawFd, RawFd};
use std::path::{Path, PathBuf};

// --------------------------------------------------------------------------
// ProotOptions
// --------------------------------------------------------------------------

/// Runtime options for entering a proot container. Loaded from
/// `<filesDir>/proot_options/container_<id>.json` by the JSON loader below.
#[derive(Debug, Clone)]
pub struct ProotOptions {
    /// Desired guest username. Empty or "root" → root.
    pub username: String,
    /// Hostname reported by `hostname` inside the container.
    pub hostname: String,
    /// Bind the host home directory into the guest user's home.
    pub shared_home: bool,
    /// Force‑shared /tmp. Always true; kept for completeness.
    pub shared_tmp: bool,
    /// Custom kernel release string. Empty → default.
    pub kernel_release: String,
}

impl Default for ProotOptions {
    fn default() -> Self {
        Self {
            username: "root".into(),
            hostname: "localhost".into(),
            shared_home: false,
            shared_tmp: true, // forced
            kernel_release: String::new(),
        }
    }
}

// --------------------------------------------------------------------------
// JSON loader (no external crate — tiny parser good enough for flat objects)
// --------------------------------------------------------------------------

fn proot_options_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("proot_options")
}

fn container_id_from_rootfs(rootfs: &Path) -> Option<i32> {
    // rootfs == <filesDir>/containers/<id>
    rootfs.file_name()?.to_str()?.parse::<i32>().ok()
}

/// Extract a JSON field by key. Handles string and bool/number values.
/// Sufficient for our flat `{ "k": "v", ... }` files.
fn read_json_field(content: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\"", key);
    let start = content.find(&needle)?;
    let after = &content[start + needle.len()..];
    let colon = after.find(':')?;
    let rest = after[colon + 1..].trim_start();
    if let Some(stripped) = rest.strip_prefix('"') {
        let end = stripped.find('"')?;
        Some(stripped[..end].to_string())
    } else {
        // bool / number
        let end = rest
            .find(|c: char| c == ',' || c == '}' || c.is_whitespace())
            .unwrap_or(rest.len());
        Some(rest[..end].to_string())
    }
}

fn load_proot_options_for(rootfs: &Path) -> ProotOptions {
    let mut opts = ProotOptions::default();

    let data_dir = match get_application_context() {
        Ok(ctx) => ctx.data_dir.clone(),
        Err(_) => return opts,
    };
    let Some(container_id) = container_id_from_rootfs(rootfs) else {
        return opts;
    };

    let file = proot_options_dir(&data_dir).join(format!("container_{}.json", container_id));
    let Ok(content) = fs::read_to_string(&file) else {
        log::info!(
            "proot: no options file for container {} — using defaults",
            container_id
        );
        return opts;
    };

    if let Some(v) = read_json_field(&content, "username") {
        if !v.trim().is_empty() {
            opts.username = v;
        }
    }
    if let Some(v) = read_json_field(&content, "hostname") {
        if !v.trim().is_empty() {
            opts.hostname = v;
        }
    }
    if let Some(v) = read_json_field(&content, "shared_home") {
        opts.shared_home = v == "true";
    }
    // shared_tmp is always forced true by the argv builder — ignore file value.
    if let Some(v) = read_json_field(&content, "kernel_release") {
        opts.kernel_release = v;
    }

    log::info!(
        "proot: loaded options for container {} → user={:?} host={:?} shared_home={} kernel={:?}",
        container_id,
        opts.username,
        opts.hostname,
        opts.shared_home,
        opts.kernel_release
    );
    opts
}

// --------------------------------------------------------------------------
// Constants
// --------------------------------------------------------------------------

const DEFAULT_FAKE_KERNEL_RELEASE: &str = "6.17.0-PRoot-Distro";
const DEFAULT_FAKE_KERNEL_VERSION: &str =
    "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000";

// --------------------------------------------------------------------------
// Pulse / profile helpers
// --------------------------------------------------------------------------

const PULSE_CLIENT_NO_SHM: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Host Pulse uses socket IPC only (no memfd across namespaces).\n\
enable-shm = no\n\
enable-memfd = no\n\
";

const GUEST_PROFILE_XODOS2_RUNTIME: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Keep essential runtime defaults across `su - user`.\n\
\n\
: \"${XDG_RUNTIME_DIR:=/run/user/0}\"\n\
: \"${PULSE_SERVER:=unix:/run/xodos2-pulse/native}\"\n\
export XDG_RUNTIME_DIR PULSE_SERVER\n\
\n\
if ! pgrep -x dbus-daemon >/dev/null 2>&1; then\n\
    dbus-daemon --system --fork 2>/dev/null || true\n\
    if command -v dbus-launch >/dev/null 2>&1; then\n\
        eval \"$(dbus-launch --sh-syntax)\" 2>/dev/null || true\n\
        export DBUS_SESSION_BUS_ADDRESS\n\
    fi\n\
fi\n\
\n\
if command -v pactl >/dev/null 2>&1; then\n\
  for _i in 1 2 3 4 5 6 7 8 9 10; do\n\
    pactl info >/dev/null 2>&1 && break\n\
    sleep 0.1\n\
  done\n\
  pactl set-default-sink xodos2-out >/dev/null 2>&1 || true\n\
fi\n\
";

fn write_pulse_guest_client_fragment(data_dir: &Path) -> PathBuf {
    let path = data_dir.join("proot_pulse_client_no_shm.conf");
    if let Err(e) = fs::write(&path, PULSE_CLIENT_NO_SHM) {
        log::warn!("proot: write {:?}: {:?}", path, e);
    }
    path
}

fn write_guest_profile_fragment(data_dir: &Path) -> PathBuf {
    let path = data_dir.join("proot_profile_xodos2_runtime.sh");
    if let Err(e) = fs::write(&path, GUEST_PROFILE_XODOS2_RUNTIME) {
        log::warn!("proot: write {:?}: {:?}", path, e);
    }
    path
}

fn proot_and_loader_paths() -> Result<(PathBuf, PathBuf)> {
    let ctx = get_application_context()?;
    let proot = ctx.native_library_dir.join("libproot.so");
    let loader = ctx.native_library_dir.join("libproot_loader.so");
    if !proot.exists() {
        anyhow::bail!("proot not found: {:?}", proot);
    }
    if !loader.exists() {
        anyhow::bail!("loader not found: {:?}", loader);
    }
    log::info!("proot: using proot={:?}, loader={:?}", proot, loader);
    Ok((proot, loader))
}

fn is_proot_compatible(rootfs: &Path) -> bool {
    let compatible = has_rootfs(rootfs)
        && rootfs.join("sys/.empty").is_dir()
        && (rootfs.join("etc/os-release").exists()
            || (rootfs.join("usr/bin").is_dir() && rootfs.join("root").is_dir()));
    log::info!("proot: rootfs {:?} proot_compatible={}", rootfs, compatible);
    compatible
}

/// Returns true if the container rootfs contains a Termux installation
/// under /data/data/com.termux/files/usr.
fn has_termux_inside_container(rootfs: &Path) -> bool {
    let termux_usr = rootfs.join("data/data/com.termux/files/usr");
    let termux_bin = termux_usr.join("bin");
    let exists = termux_usr.is_dir() && termux_bin.is_dir();
    log::info!(
        "proot: rootfs {:?} has_termux_inside_container={}",
        rootfs,
        exists
    );
    exists
}

fn current_uid_gid() -> (u32, u32) {
    unsafe { (nix::libc::getuid(), nix::libc::getgid()) }
}

fn ensure_fake_sysdata(rootfs: &Path) -> Result<()> {
    let sysdata_dir = rootfs
        .parent()
        .context("rootfs parent directory")?
        .join("sysdata");
    fs::create_dir_all(&sysdata_dir)?;
    fs::set_permissions(&sysdata_dir, PermissionsExt::from_mode(0o700))?;

    let sys_empty = rootfs.join("sys/.empty");
    fs::create_dir_all(&sys_empty)?;

    let write_if_missing = |path: &Path, content: &str| -> Result<()> {
        if !path.exists() {
            fs::write(path, content)?;
        }
        Ok(())
    };

    write_if_missing(&sysdata_dir.join("loadavg"), "0.12 0.07 0.02 2/165 765\n")?;
    write_if_missing(
        &sysdata_dir.join("stat"),
        "cpu  1957 0 2877 93280 262 342 254 87 0 0\n\
         ctxt 140223\n\
         btime 1680020856\n\
         processes 772\n\
         procs_running 2\n\
         procs_blocked 0\n",
    )?;
    write_if_missing(&sysdata_dir.join("uptime"), "124.08 932.80\n")?;
    write_if_missing(
        &sysdata_dir.join("version"),
        &format!(
            "Linux version {} (proot@xodos2) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) {}\n",
            DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION
        ),
    )?;
    write_if_missing(&sysdata_dir.join("vmstat"), "nr_free_pages 1743136\n")?;
    write_if_missing(&sysdata_dir.join("sysctl_entry_cap_last_cap"), "40\n")?;
    write_if_missing(
        &sysdata_dir.join("sysctl_inotify_max_user_watches"),
        "4096\n",
    )?;
    write_if_missing(&sysdata_dir.join("sysctl_kernel_overflowuid"), "65534\n")?;
    write_if_missing(&sysdata_dir.join("sysctl_kernel_overflowgid"), "65534\n")?;
    Ok(())
}

fn fake_proc_bindings(_rootfs: &Path, sysdata_dir: &Path) -> Result<Vec<CString>> {
    let mut binds = Vec::new();
    let pairs = [
        ("/proc/loadavg", "loadavg"),
        ("/proc/stat", "stat"),
        ("/proc/uptime", "uptime"),
        ("/proc/version", "version"),
        ("/proc/vmstat", "vmstat"),
        (
            "/proc/sys/kernel/cap_last_cap",
            "sysctl_entry_cap_last_cap",
        ),
        (
            "/proc/sys/fs/inotify/max_user_watches",
            "sysctl_inotify_max_user_watches",
        ),
        ("/proc/sys/kernel/overflowuid", "sysctl_kernel_overflowuid"),
        ("/proc/sys/kernel/overflowgid", "sysctl_kernel_overflowgid"),
    ];

    for (real_path, fake_name) in pairs {
        let real = Path::new(real_path);
        let readable = fs::File::open(real)
            .map(|f| f.metadata().is_ok())
            .unwrap_or(false);
        if !readable {
            let fake_file = sysdata_dir.join(fake_name);
            if fake_file.exists() {
                binds.push(
                    CString::new(format!("--bind={}:{}", fake_file.display(), real_path))
                        .context("bind fake proc")?,
                );
            }
        }
    }
    Ok(binds)
}

// --------------------------------------------------------------------------
// Shell resolution helpers
// --------------------------------------------------------------------------

/// Returns the guest path of a usable shell inside `rootfs`, plus a flag
/// indicating whether it is a BusyBox multi-call binary.
pub(super) fn find_shell_in_rootfs(rootfs: &Path) -> Option<(&'static str, bool)> {
    let termux_detected = has_termux_inside_container(rootfs);

    let candidates: Vec<(&'static str, bool)> = if termux_detected {
        vec![
            ("/data/data/com.termux/files/usr/bin/bash", false),
            ("/data/data/com.termux/files/usr/bin/sh", false),
            ("/data/data/com.termux/files/usr/bin/dash", false),
            ("/data/data/com.termux/files/usr/bin/ash", false),
        ]
    } else {
        vec![
            ("/usr/bin/bash", false),
            ("/bin/bash", false),
            ("/usr/bin/sh", false),
            ("/bin/sh", false),
            ("/usr/bin/dash", false),
            ("/bin/dash", false),
            ("/usr/bin/ash", false),
            ("/bin/ash", false),
        ]
    };

    for (path, is_busybox) in candidates {
        if path_exists_in_rootfs(rootfs, path) {
            return Some((path, is_busybox));
        }
    }

    // BusyBox fallback
    if path_exists_in_rootfs(rootfs, "/usr/bin/busybox") {
        Some(("/usr/bin/busybox", true))
    } else if path_exists_in_rootfs(rootfs, "/bin/busybox") {
        Some(("/bin/busybox", true))
    } else if termux_detected
        && path_exists_in_rootfs(rootfs, "/data/data/com.termux/files/usr/bin/busybox")
    {
        Some((
            "/data/data/com.termux/files/usr/bin/busybox",
            true,
        ))
    } else {
        None
    }
}

/// Build argv/env for the native (Termux‑style) Android shell.
fn build_native_args() -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let mut argv: Vec<CString> = Vec::new();
    let mut env: Vec<CString> = Vec::new();

    let prefix = ctx.data_dir.join("usr");
    let prefix_str = prefix.to_string_lossy().into_owned();
    let home_dir = "/data/data/app.xodos2/files/home";
    let tmp_dir = format!("{}/tmp", prefix_str);

    let _ = fs::create_dir_all(&tmp_dir);
    let _ = fs::create_dir_all(home_dir);

    #[cfg(target_pointer_width = "64")]
    let linker = "/system/bin/linker64";
    #[cfg(target_pointer_width = "32")]
    let linker = "/system/bin/linker";

    let bash_path = format!("{}/bin/bash", prefix_str);
    let sh_path = format!("{}/bin/sh", prefix_str);

    let (shell_path, is_bionic) = if Path::new(&bash_path).exists() {
        (bash_path, true)
    } else if Path::new(&sh_path).exists() {
        (sh_path, true)
    } else {
        log::warn!(
            "native shell: neither {:?} nor {:?} exists; falling back to /system/bin/sh",
            bash_path,
            sh_path
        );
        ("/system/bin/sh".to_string(), false)
    };

    if is_bionic {
        argv.push(CString::new(linker).unwrap());
    }
    argv.push(CString::new(shell_path).unwrap());
    argv.push(CString::new("-l").unwrap());

    env.extend(vec![
        CString::new(format!("PREFIX={}", prefix_str)).unwrap(),
        CString::new(format!("HOME={}", home_dir)).unwrap(),
        CString::new(format!("TMPDIR={}", tmp_dir)).unwrap(),
        CString::new(format!(
            "PATH={}/bin:/system/bin:/system/xbin",
            prefix_str
        ))
        .unwrap(),
        CString::new(format!("LD_LIBRARY_PATH={}/lib", prefix_str)).unwrap(),
        CString::new("TERM=xterm-256color").unwrap(),
        CString::new("DISPLAY=:0").unwrap(),
        CString::new("PS1=[XoDos-Ark\\W]\\$ ").unwrap(),
    ]);

    Ok((argv, env))
}

// --------------------------------------------------------------------------
// build_proot_args — respects ProotOptions
// --------------------------------------------------------------------------

fn build_proot_args(
    rootfs: &Path,
    shell_binary: &str,
    is_busybox: bool,
    opts: &ProotOptions,
) -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let mut argv: Vec<CString> = Vec::new();
    let mut env: Vec<CString> = Vec::new();

    let (proot, loader) = proot_and_loader_paths()?;
    let proot_str = proot.to_string_lossy();
    let loader_str = loader.to_string_lossy();

    // Detect Termux inside the container rootfs early.
    let termux_detected = has_termux_inside_container(rootfs);

    argv.push(CString::new(proot_str.as_bytes()).context("proot path")?);

    // 0. Write fake /proc & /sys content (outside rootfs).
    ensure_fake_sysdata(rootfs)?;
    let sysdata_dir = rootfs.parent().context("rootfs parent")?.join("sysdata");

    // 1. Basic rootfs and options.
    argv.push(CString::new("-r").unwrap());
    argv.push(CString::new(rootfs.to_string_lossy().as_bytes()).context("rootfs path")?);

    let kernel_release = if opts.kernel_release.trim().is_empty() {
        format!(
            "{} {}",
            DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION
        )
    } else {
        opts.kernel_release.clone()
    };
    argv.push(CString::new(format!("--kernel-release={}", kernel_release)).unwrap());

    argv.push(CString::new("-L").unwrap());
    argv.push(CString::new("--link2symlink").unwrap());
    argv.push(CString::new("--sysvipc").unwrap());
    argv.push(CString::new("--kill-on-exit").unwrap());

    // Conditional UID/GID change.
    if termux_detected {
        let (uid, gid) = current_uid_gid();
        argv.push(CString::new(format!("--change-id={}:{}", uid, gid)).unwrap());
    } else {
        argv.push(CString::new("--change-id=0:0").unwrap());
    }

    // Hostname: write /etc/hostname so `hostname` reports it.
    if !opts.hostname.trim().is_empty() {
        let hostname_file = rootfs.join("etc/hostname");
        if let Some(parent) = hostname_file.parent() {
            fs::create_dir_all(parent).ok();
        }
        if let Err(e) = fs::write(&hostname_file, format!("{}\n", opts.hostname.trim())) {
            log::warn!("proot: failed to write /etc/hostname: {:?}", e);
        }
    }

    // Core binds – conditional /data handling.
    argv.push(CString::new("--bind=/dev").unwrap());

    if termux_detected {
        let host_xodos_home2 = Path::new("/data/data/app.xodos2/files/home");
        if host_xodos_home2.exists() {
            let guest_path = "/data/data/com.termux/files/home";
            argv.push(
                CString::new(format!(
                    "--bind={}:{}",
                    host_xodos_home2.display(),
                    guest_path
                ))
                .unwrap(),
            );
            log::info!(
                "proot: Termux inside container, bound host home {} to {}",
                host_xodos_home2.display(),
                guest_path
            );
        } else {
            log::warn!(
                "proot: host Termux home {} does not exist; skipping bind",
                host_xodos_home2.display()
            );
        }
    } else {
        let host_app_home = Path::new("/data/data/app.xodos2/files/home");
        let _ = fs::create_dir_all(host_app_home);

        if opts.shared_home {
            // Bind host home into the guest user's home directory.
            let guest_home = if opts.username.trim().is_empty() || opts.username.trim() == "root"
            {
                "/root".to_string()
            } else {
                format!("/home/{}", opts.username.trim())
            };
            argv.push(
                CString::new(format!(
                    "--bind={}:{}",
                    host_app_home.display(),
                    guest_home
                ))
                .unwrap(),
            );
            log::info!(
                "proot: shared home bound {} to {}",
                host_app_home.display(),
                guest_home
            );
        } else {
            argv.push(
                CString::new(format!("--bind={}:/root", host_app_home.display())).unwrap(),
            );
        }
    }

    argv.push(CString::new("--bind=/proc").unwrap());
    argv.push(CString::new("--bind=/sys").unwrap());
    argv.push(CString::new("--bind=/system").unwrap());
    argv.push(CString::new("--bind=/apex").unwrap());
    argv.push(CString::new("--bind=/storage").unwrap());

    // GPU / DRM.
    if Path::new("/dev/kgsl-3d0").exists() {
        argv.push(CString::new("--bind=/dev/kgsl-3d0:/dev/kgsl-3d0").unwrap());
    }
    if Path::new("/dev/dri").exists() && File::open("/dev/dri").is_ok() {
        argv.push(CString::new("--bind=/dev/dri:/dev/dri").unwrap());
    } else {
        let dummy_dri = ctx.data_dir.join("usr").join("tmp");
        fs::create_dir_all(&dummy_dri)?;
        argv.push(CString::new(format!("--bind={}:/dev/dri", dummy_dri.display())).unwrap());
    }

    // Wayland / X11.
    let wayland_runtime = ctx.data_dir.join("usr").join("tmp");
    fs::create_dir_all(&wayland_runtime)?;
    fs::set_permissions(&wayland_runtime, PermissionsExt::from_mode(0o700))?;

    let host_x11_dir = ctx.data_dir.join("usr/tmp").join(".X11-unix");
    fs::create_dir_all(&host_x11_dir)?;
    let guest_x11_dir = rootfs.join("tmp/.X11-unix");
    fs::create_dir_all(&guest_x11_dir)?;
    fs::set_permissions(&host_x11_dir, PermissionsExt::from_mode(0o1777))?;
    fs::set_permissions(&guest_x11_dir, PermissionsExt::from_mode(0o1777))?;

    argv.push(
        CString::new(format!(
            "--bind={}:/run/user/0",
            wayland_runtime.display()
        ))
        .unwrap(),
    );
    argv.push(
        CString::new(format!(
            "--bind={}:{}",
            host_x11_dir.display(),
            "/tmp/.X11-unix"
        ))
        .unwrap(),
    );

    // VirGL.
    let virgl_runtime = ctx.data_dir.join("virgl-run");
    fs::create_dir_all(&virgl_runtime)?;
    argv.push(
        CString::new(format!(
            "--bind={}:/run/xodos2-virgl",
            virgl_runtime.display()
        ))
        .unwrap(),
    );

    // PulseAudio.
    let pulse_rt = host_pulse_runtime_dir(&ctx.data_dir);
    fs::create_dir_all(&pulse_rt)?;
    argv.push(
        CString::new(format!(
            "--bind={}:{}",
            pulse_rt.display(),
            GUEST_PULSE_RUNTIME_MOUNT
        ))
        .unwrap(),
    );
    let pulse_client_frag = write_pulse_guest_client_fragment(&ctx.data_dir);
    argv.push(
        CString::new(format!(
            "--bind={}:/etc/pulse/client.conf.d/99-xodos2-noshm.conf",
            pulse_client_frag.display()
        ))
        .unwrap(),
    );
    let profile_frag = write_guest_profile_fragment(&ctx.data_dir);
    argv.push(
        CString::new(format!(
            "--bind={}:/etc/profile.d/99-xodos2-runtime.sh",
            profile_frag.display()
        ))
        .unwrap(),
    );

    // /dev/shm.
    argv.push(CString::new(format!("--bind={}/tmp:/dev/shm", rootfs.display())).unwrap());

    // ────────────────────────────────────────────────────────────────
    // FORCED shared /tmp — always bind host PREFIX/tmp to guest /tmp.
    // ────────────────────────────────────────────────────────────────
    let host_tmp = ctx.data_dir.join("usr").join("tmp");
    fs::create_dir_all(&host_tmp)?;
    fs::set_permissions(&host_tmp, PermissionsExt::from_mode(0o1777))?;
    argv.push(CString::new(format!("--bind={}:/tmp", host_tmp.display())).unwrap());
    log::info!("proot: forced shared /tmp -> {}", host_tmp.display());

    // SD card.
    if let Some(ref sdcard) = ctx.external_storage_path {
        if sdcard.exists() {
            for (guest, host) in [
                ("/android", sdcard),
                ("/root/android", sdcard),
                ("/sdcard", sdcard),
                ("/root/sdcard", sdcard),
            ] {
                argv.push(
                    CString::new(format!("--bind={}:{}", host.display(), guest)).unwrap(),
                );
            }
        }
    }

    // Standard fds.
    argv.push(CString::new("--bind=/dev/urandom:/dev/random").unwrap());
    argv.push(CString::new("--bind=/proc/self/fd:/dev/fd").unwrap());
    argv.push(CString::new("--bind=/proc/self/fd/0:/dev/stdin").unwrap());
    argv.push(CString::new("--bind=/proc/self/fd/1:/dev/stdout").unwrap());
    argv.push(CString::new("--bind=/proc/self/fd/2:/dev/stderr").unwrap());

    // Fake proc.
    let fake_binds = fake_proc_bindings(rootfs, &sysdata_dir)?;
    argv.extend(fake_binds);

    // Fake SELinux.
    argv.push(
        CString::new(format!(
            "--bind={}/sys/.empty:/sys/fs/selinux",
            rootfs.display()
        ))
        .unwrap(),
    );

    // Extra Android dirs.
    for path in [
        "/vendor",
        "/odm",
        "/product",
        "/system_ext",
        "/linkerconfig/ld.config.txt",
        "/plat_property_contexts",
        "/property_contexts",
    ] {
        if Path::new(path).exists() {
            argv.push(CString::new(format!("--bind={}", path)).unwrap());
        }
    }

    // Ensure /etc/resolv.conf and /etc/hosts exist.
    let resolv_conf = rootfs.join("etc/resolv.conf");
    if !resolv_conf.exists() {
        if let Some(parent) = resolv_conf.parent() {
            fs::create_dir_all(parent).ok();
        }
        if let Err(e) = fs::write(&resolv_conf, "nameserver 8.8.8.8\nnameserver 8.8.4.4\n") {
            log::warn!("proot: failed to write resolv.conf: {:?}", e);
        }
    }
    let hosts = rootfs.join("etc/hosts");
    if !hosts.exists() {
        if let Some(parent) = hosts.parent() {
            fs::create_dir_all(parent).ok();
        }
        if let Err(e) = fs::write(
            &hosts,
            "127.0.0.1 localhost.localdomain localhost\n::1 localhost.localdomain localhost ip6-localhost ip6-loopback\n",
        ) {
            log::warn!("proot: failed to write hosts: {:?}", e);
        }
    }

    // ---- Shell exec ----
    if is_busybox {
        argv.push(CString::new(shell_binary).unwrap());
        argv.push(CString::new("sh").unwrap());
        argv.push(CString::new("-i").unwrap());
    } else {
        argv.push(CString::new(shell_binary).unwrap());
        argv.push(CString::new("-l").unwrap());
        argv.push(CString::new("-i").unwrap());
    }

    // ── Environment variables ──
    env.push(CString::new(format!("PROOT_LOADER={}", loader_str)).unwrap());
    env.push(CString::new(format!("PROOT_TMP_DIR={}", ctx.cache_dir.display())).unwrap());

    // Effective user
    let effective_user = if opts.username.trim().is_empty() || opts.username.trim() == "root" {
        "root".to_string()
    } else {
        opts.username.trim().to_string()
    };

    // HOME
    let home_dir = if termux_detected {
        "/data/data/com.termux/files/home".to_string()
    } else if effective_user == "root" {
        "/root".to_string()
    } else {
        format!("/home/{}", effective_user)
    };
    env.push(CString::new(format!("HOME={}", home_dir)).unwrap());

    // PATH
    let default_path = if termux_detected {
        "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/sbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin"
    } else {
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/data/data/app.xodos2/files/usr/bin"
    };
    env.push(CString::new(format!("PATH={}", default_path)).unwrap());

    // USER / LOGNAME / HOSTNAME
    env.push(CString::new(format!("USER={}", effective_user)).unwrap());
    env.push(CString::new(format!("LOGNAME={}", effective_user)).unwrap());
    env.push(CString::new(format!("HOSTNAME={}", opts.hostname.trim())).unwrap());

    env.push(CString::new("TERM=xterm-256color").unwrap());
    env.push(CString::new("LANG=C.UTF-8").unwrap());
    env.push(CString::new(format!("SHELL={}", shell_binary)).unwrap());
    env.push(CString::new("TMPDIR=/tmp").unwrap());
    env.push(CString::new("XDG_RUNTIME_DIR=/run/user/0").unwrap());
    env.push(CString::new("WAYLAND_DISPLAY=wayland-xodos2").unwrap());
    env.push(CString::new("XDG_SESSION_TYPE=wayland").unwrap());
    env.push(CString::new("QT_QPA_PLATFORM=wayland").unwrap());
    env.push(CString::new("QT_QUICK_BACKEND=software").unwrap());
    env.push(CString::new("VTEST_SOCKET_NAME=/run/xodos2-virgl/vtest.sock").unwrap());
    env.push(
        CString::new(format!("PULSE_SERVER={}", guest_pulse_server_env()))
            .context("PULSE_SERVER")?,
    );

    if rootfs.join("nix/store").is_dir() {
        if let Some(pos) = env
            .iter()
            .position(|s| s.to_str().map_or(false, |v| v.starts_with("PATH=")))
        {
            let current_path = env[pos].to_str().unwrap_or("PATH=").to_string();
            let new_path = format!(
                "PATH=/root/.nix-profile/bin:/run/current-system/sw/bin:{}",
                &current_path[5..]
            );
            env[pos] = CString::new(new_path).unwrap();
        }
        env.push(CString::new("ENV=/root/.bashrc").unwrap());
    }

    log::debug!(
        "proot: final argv = {:?}",
        argv.iter()
            .map(|s| s.to_string_lossy().into_owned())
            .collect::<Vec<_>>()
    );
    log::debug!(
        "proot: final env = {:?}",
        env.iter()
            .map(|s| s.to_string_lossy().into_owned())
            .collect::<Vec<_>>()
    );

    Ok((argv, env))
}

// --------------------------------------------------------------------------
// Public dispatcher — reads ProotOptions internally, so the JNI signature
// stays exactly the same (rootfs, rows, cols).
// --------------------------------------------------------------------------

pub(super) fn build_exec_args(
    rootfs: &Path,
    opts: &ProotOptions,
) -> Result<(Vec<CString>, Vec<CString>)> {
    if is_proot_compatible(rootfs) {
        if let Some((shell_binary, is_busybox)) = find_shell_in_rootfs(rootfs) {
            log::info!(
                "proot: using container shell {:?} (busybox={}) in {:?}",
                shell_binary,
                is_busybox,
                rootfs
            );
            return build_proot_args(rootfs, shell_binary, is_busybox, opts);
        }
        log::warn!(
            "proot: rootfs {:?} is compatible but has no usable shell — \
             falling back to native Android shell",
            rootfs
        );
    } else {
        log::warn!(
            "proot: rootfs {:?} is not proot-compatible — using native Android shell",
            rootfs
        );
    }

    build_native_args()
}

pub struct ChildProcess {
    pub pid: Pid,
}

impl Drop for ChildProcess {
    fn drop(&mut self) {
        let _ = nix::sys::signal::kill(self.pid, nix::sys::signal::Signal::SIGTERM);
    }
}

fn fork_pty_with_argv(
    argv: Vec<CString>,
    env: Vec<CString>,
    initial_rows: u16,
    initial_cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let argv_refs: Vec<&CStr> = argv.iter().map(|s| s.as_c_str()).collect();
    let env_refs: Vec<&CStr> = env.iter().map(|s| s.as_c_str()).collect();

    let winsize = Winsize {
        ws_row: initial_rows.max(1),
        ws_col: initial_cols.max(1),
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    let result = unsafe { forkpty(Some(&winsize), None).context("forkpty failed")? };

    match result {
        ForkptyResult::Child => {
            log::debug!("proot: child about to execve: {:?}", argv_refs);
            if execve(argv[0].as_c_str(), &argv_refs, &env_refs).is_err() {
                log::error!(
                    "proot: execve failed: {:?}",
                    std::io::Error::last_os_error()
                );
                unsafe { nix::libc::_exit(1) };
            }
            unreachable!();
        }
        ForkptyResult::Parent { child, master } => {
            log::debug!("proot: forkpty succeeded, child pid = {}", child);
            let master_read_fd = dup(&master).context("dup master for read")?.into_raw_fd();
            let master_write_fd = master.into_raw_fd();
            let master_read = unsafe { File::from_raw_fd(master_read_fd) };
            let master_write = unsafe { File::from_raw_fd(master_write_fd) };
            let stdin: Box<dyn Write + Send> = Box::new(master_write);
            Ok((
                ChildProcess { pid: child },
                master_read,
                stdin,
                master_write_fd,
            ))
        }
    }
}

/// Spawn the shell for `rootfs`, applying the full fallback chain:
/// container shell (inside proot) → native Termux‑style shell → `/system/bin/sh`.
///
/// **No JNI changes required** — the ProotOptions JSON file is read here,
/// based on the container id derived from the rootfs path.
pub fn fork_pty_shell_in_rootfs(
    rootfs: &Path,
    initial_rows: u16,
    initial_cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let opts = load_proot_options_for(rootfs);
    let (argv, env) = build_exec_args(rootfs, &opts)?;
    fork_pty_with_argv(argv, env, initial_rows, initial_cols)
}

/// Spawn the native Termux‑style Android shell with no proot.
pub fn fork_pty_native_shell(
    initial_rows: u16,
    initial_cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let (argv, env) = build_native_args()?;
    fork_pty_with_argv(argv, env, initial_rows, initial_cols)
}

pub(super) fn path_exists_in_rootfs(rootfs: &Path, relative_path: &str) -> bool {
    let full_path = rootfs.join(relative_path.trim_start_matches('/'));
    if full_path.exists() {
        return true;
    }
    if full_path.is_symlink() {
        if let Ok(target) = fs::read_link(&full_path) {
            let resolved = if target.is_relative() {
                full_path.parent().unwrap_or(Path::new("/")).join(target)
            } else {
                let target_str = target.to_string_lossy();
                if target_str.starts_with('/') {
                    rootfs.join(&target_str[1..])
                } else {
                    rootfs.join(target_str.as_ref())
                }
            };
            return resolved.exists();
        }
    }
    false
}