//! proot.rs — Proot argv/env with full proot-distro-style options.
//!
//! Options live in `<filesDir>/proot_options/container_<id>.json`.
//! Users are switched **directly** via `--change-id` (fake_id0), never via
//! a layered `su` shell.

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

// ==========================================================================
// ProotOptions
// ==========================================================================

#[derive(Debug, Clone)]
pub struct ProotOptions {
    pub username: String,
    pub hostname: String,
    pub shared_home: bool,
    pub shared_tmp: bool,        // always forced true by the builder
    pub kernel_release: String,
    pub isolated: bool,
    pub minimal: bool,
    pub no_sysvipc: bool,
    pub no_link2symlink: bool,
    pub change_id: String,       // e.g. "0:0" — overrides everything if set
    pub cwd: String,
    pub binds: Vec<String>,      // "SRC:DST"
    pub envs: Vec<String>,       // "KEY=VALUE"
}

impl Default for ProotOptions {
    fn default() -> Self {
        Self {
            username: "root".into(),
            hostname: "localhost".into(),
            shared_home: true,
            shared_tmp: true,
            kernel_release: String::new(),
            isolated: false,
            minimal: false,
            no_sysvipc: false,
            no_link2symlink: false,
            change_id: String::new(),
            cwd: String::new(),
            binds: Vec::new(),
            envs: Vec::new(),
        }
    }
}

// ==========================================================================
// JSON loader
// ==========================================================================

fn proot_options_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("proot_options")
}

fn container_id_from_rootfs(rootfs: &Path) -> Option<i32> {
    rootfs.file_name()?.to_str()?.parse::<i32>().ok()
}

fn read_json_field(content: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\"", key);
    let start = content.find(&needle)?;
    let after = &content[start + needle.len()..];
    let colon = after.find(':')?;
    let rest = after[colon + 1..].trim_start();
    if let Some(s) = rest.strip_prefix('"') {
        let end = s.find('"')?;
        Some(s[..end].to_string())
    } else {
        let end = rest
            .find(|c: char| c == ',' || c == '}' || c.is_whitespace())
            .unwrap_or(rest.len());
        Some(rest[..end].to_string())
    }
}

fn read_json_array(content: &str, key: &str) -> Vec<String> {
    let needle = format!("\"{}\"", key);
    let Some(start) = content.find(&needle) else { return Vec::new() };
    let after = &content[start + needle.len()..];
    let Some(colon) = after.find(':') else { return Vec::new() };
    let rest = after[colon + 1..].trim_start();
    let Some(stripped) = rest.strip_prefix('[') else { return Vec::new() };
    let Some(end) = stripped.find(']') else { return Vec::new() };
    let inside = &stripped[..end];

    let mut out = Vec::new();
    let mut current = String::new();
    let mut in_string = false;
    let mut escape = false;
    for c in inside.chars() {
        if escape {
            current.push(c);
            escape = false;
        } else if c == '\\' && in_string {
            escape = true;
        } else if c == '"' {
            if in_string {
                out.push(std::mem::take(&mut current));
                in_string = false;
            } else {
                in_string = true;
            }
        } else if in_string {
            current.push(c);
        }
    }
    out
}

fn load_proot_options_for(rootfs: &Path) -> ProotOptions {
    let mut opts = ProotOptions::default();
    let Ok(ctx) = get_application_context() else { return opts };
    let Some(cid) = container_id_from_rootfs(rootfs) else { return opts };
    let file = proot_options_dir(&ctx.data_dir).join(format!("container_{}.json", cid));
    let Ok(content) = fs::read_to_string(&file) else {
        log::info!("proot: no options for c{} — defaults (shared_home=true)", cid);
        return opts;
    };

    if let Some(v) = read_json_field(&content, "username") {
        if !v.trim().is_empty() { opts.username = v; }
    }
    if let Some(v) = read_json_field(&content, "hostname") {
        if !v.trim().is_empty() { opts.hostname = v; }
    }
    opts.shared_home = read_json_field(&content, "shared_home")
        .map(|v| v == "true")
        .unwrap_or(true);
    if let Some(v) = read_json_field(&content, "kernel_release") { opts.kernel_release = v; }
    opts.isolated = read_json_field(&content, "isolated").map(|v| v == "true").unwrap_or(false);
    opts.minimal  = read_json_field(&content, "minimal").map(|v| v == "true").unwrap_or(false);
    opts.no_sysvipc     = read_json_field(&content, "no_sysvipc").map(|v| v == "true").unwrap_or(false);
    opts.no_link2symlink = read_json_field(&content, "no_link2symlink").map(|v| v == "true").unwrap_or(false);
    if let Some(v) = read_json_field(&content, "change_id") { opts.change_id = v; }
    if let Some(v) = read_json_field(&content, "cwd")       { opts.cwd = v; }
    opts.binds = read_json_array(&content, "binds");
    opts.envs  = read_json_array(&content, "envs");

    log::info!(
        "proot: c{} user={:?} host={:?} shared_home={} isolated={} minimal={} binds={} envs={}",
        cid, opts.username, opts.hostname, opts.shared_home,
        opts.isolated, opts.minimal, opts.binds.len(), opts.envs.len()
    );
    opts
}

// ==========================================================================
// Constants
// ==========================================================================

const DEFAULT_FAKE_KERNEL_RELEASE: &str = "6.17.0-PRoot-Distro";
const DEFAULT_FAKE_KERNEL_VERSION: &str =
    "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000";

const PULSE_CLIENT_NO_SHM: &str = "\
enable-shm = no\nenable-memfd = no\n\
";

const GUEST_PROFILE_XODOS2_RUNTIME: &str = "\
: \"${XDG_RUNTIME_DIR:=/run/user/0}\"\n\
: \"${PULSE_SERVER:=unix:/run/xodos2-pulse/native}\"\n\
export XDG_RUNTIME_DIR PULSE_SERVER\n\
if ! pgrep -x dbus-daemon >/dev/null 2>&1; then\n\
  dbus-daemon --system --fork 2>/dev/null || true\n\
  if command -v dbus-launch >/dev/null 2>&1; then\n\
    eval \"$(dbus-launch --sh-syntax)\" 2>/dev/null || true\n\
    export DBUS_SESSION_BUS_ADDRESS\n\
  fi\n\
fi\n\
if command -v pactl >/dev/null 2>&1; then\n\
  for _i in 1 2 3 4 5 6 7 8 9 10; do pactl info >/dev/null 2>&1 && break; sleep 0.1; done\n\
  pactl set-default-sink xodos2-out >/dev/null 2>&1 || true\n\
fi\n\
";

// ==========================================================================
// Small helpers
// ==========================================================================

fn proot_and_loader_paths() -> Result<(PathBuf, PathBuf)> {
    let ctx = get_application_context()?;
    let proot = ctx.native_library_dir.join("libproot.so");
    let loader = ctx.native_library_dir.join("libproot_loader.so");
    if !proot.exists()  { anyhow::bail!("proot not found: {:?}", proot); }
    if !loader.exists() { anyhow::bail!("loader not found: {:?}", loader); }
    Ok((proot, loader))
}

fn is_proot_compatible(rootfs: &Path) -> bool {
    has_rootfs(rootfs)
        && rootfs.join("sys/.empty").is_dir()
        && (rootfs.join("etc/os-release").exists()
            || (rootfs.join("usr/bin").is_dir() && rootfs.join("root").is_dir()))
}

fn has_termux_inside_container(rootfs: &Path) -> bool {
    rootfs.join("data/data/com.termux/files/usr/bin").is_dir()
}

fn current_uid_gid() -> (u32, u32) {
    unsafe { (nix::libc::getuid(), nix::libc::getgid()) }
}

fn write_pulse_guest_client_fragment(data_dir: &Path) -> PathBuf {
    let path = data_dir.join("proot_pulse_client_no_shm.conf");
    let _ = fs::write(&path, PULSE_CLIENT_NO_SHM);
    path
}

fn write_guest_profile_fragment(data_dir: &Path) -> PathBuf {
    let path = data_dir.join("proot_profile_xodos2_runtime.sh");
    let _ = fs::write(&path, GUEST_PROFILE_XODOS2_RUNTIME);
    path
}

fn write_fake_hostname(data_dir: &Path, hostname: &str) -> PathBuf {
    let path = data_dir.join("fake_hostname");
    let _ = fs::write(&path, format!("{}\n", hostname.trim()));
    path
}

// ==========================================================================
// User setup — DIRECT UID mapping (no su)
// ==========================================================================

/// Ensures `username` exists in passwd/group with uid=gid=1000, creates
/// /home/username with 0777 so the fake-uid-1000 process can write there,
/// and returns the (uid, gid) pair to use with `--change-id`.
fn ensure_user_in_rootfs(rootfs: &Path, username: &str) -> Result<(u32, u32)> {
    if username.trim().is_empty() || username.trim() == "root" {
        return Ok((0, 0));
    }
    let user = username.trim();
    let uid: u32 = 1000;
    let gid: u32 = 1000;

    // passwd
    let passwd = rootfs.join("etc/passwd");
    if let Some(p) = passwd.parent() { let _ = fs::create_dir_all(p); }
    let content = fs::read_to_string(&passwd).unwrap_or_default();
    if !content.lines().any(|l| l.starts_with(&format!("{}:", user))) {
        let entry = format!("{}:x:{}:{}:{}:/home/{}:/bin/bash\n", user, uid, gid, user, user);
        fs::OpenOptions::new().create(true).append(true).open(&passwd)?
            .write_all(entry.as_bytes())?;
    }

    // group
    let group = rootfs.join("etc/group");
    if let Some(p) = group.parent() { let _ = fs::create_dir_all(p); }
    let g = fs::read_to_string(&group).unwrap_or_default();
    if !g.lines().any(|l| l.starts_with(&format!("{}:", user))) {
        let entry = format!("{}:x:{}:\n", user, gid);
        fs::OpenOptions::new().create(true).append(true).open(&group)?
            .write_all(entry.as_bytes())?;
    }

    // home dir + 0777 so fake-uid-1000 can write under proot permission emulation
    let home = rootfs.join(format!("home/{}", user));
    fs::create_dir_all(&home)?;
    let _ = fs::set_permissions(&home, PermissionsExt::from_mode(0o777));

    Ok((uid, gid))
}

// ==========================================================================
// Hostname prompt override
// ==========================================================================

fn inject_hostname_profile(rootfs: &Path, hostname: &str) {
    let profile_d = rootfs.join("etc/profile.d");
    if let Err(e) = fs::create_dir_all(&profile_d) {
        log::warn!("proot: profile.d: {:?}", e);
        return;
    }
    let script = profile_d.join("zz-xodos2-hostname.sh");
    let content = format!(
        r#"#!/bin/sh
HOSTNAME="{}"
export HOSTNAME
# Rewrite \h and \H in the current PS1 to the literal hostname.
if [ -n "$PS1" ]; then
    PS1=$(printf '%s' "$PS1" | sed "s/\\\\h/{}/g; s/\\\\H/{}/g")
    export PS1
fi
"#,
        hostname.trim(), hostname.trim(), hostname.trim()
    );
    if let Err(e) = fs::write(&script, content) {
        log::warn!("proot: write hostname profile: {:?}", e);
    } else {
        let _ = fs::set_permissions(&script, PermissionsExt::from_mode(0o755));
    }

    let bashrc = rootfs.join("etc/bash.bashrc");
    let marker = "# XoDos-Ark hostname override";
    let existing = fs::read_to_string(&bashrc).unwrap_or_default();
    if !existing.contains(marker) {
        let snippet = format!(
            "\n{}\n[ -r /etc/profile.d/zz-xodos2-hostname.sh ] && . /etc/profile.d/zz-xodos2-hostname.sh\n",
            marker
        );
        let _ = fs::OpenOptions::new().create(true).append(true).open(&bashrc)
            .and_then(|mut f| f.write_all(snippet.as_bytes()));
    }
}

// ==========================================================================
// Fake /proc + /sys data
// ==========================================================================

fn ensure_fake_sysdata(rootfs: &Path) -> Result<()> {
    let sysdata_dir = rootfs.parent().context("rootfs parent")?.join("sysdata");
    fs::create_dir_all(&sysdata_dir)?;
    let _ = fs::set_permissions(&sysdata_dir, PermissionsExt::from_mode(0o700));
    fs::create_dir_all(rootfs.join("sys/.empty"))?;

    let w = |p: &Path, c: &str| { if !p.exists() { let _ = fs::write(p, c); } };
    w(&sysdata_dir.join("loadavg"), "0.12 0.07 0.02 2/165 765\n");
    w(&sysdata_dir.join("stat"),
      "cpu  1957 0 2877 93280 262 342 254 87 0 0\nctxt 140223\nbtime 1680020856\nprocesses 772\nprocs_running 2\nprocs_blocked 0\n");
    w(&sysdata_dir.join("uptime"), "124.08 932.80\n");
    w(&sysdata_dir.join("version"),
      &format!("Linux version {} (proot@xodos2) (gcc 13.3.0) {}\n",
               DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION));
    w(&sysdata_dir.join("vmstat"), "nr_free_pages 1743136\n");
    w(&sysdata_dir.join("sysctl_entry_cap_last_cap"), "40\n");
    w(&sysdata_dir.join("sysctl_inotify_max_user_watches"), "4096\n");
    w(&sysdata_dir.join("sysctl_kernel_overflowuid"), "65534\n");
    w(&sysdata_dir.join("sysctl_kernel_overflowgid"), "65534\n");
    Ok(())
}

fn fake_proc_bindings(sysdata_dir: &Path) -> Result<Vec<CString>> {
    let mut binds = Vec::new();
    let pairs = [
        ("/proc/loadavg", "loadavg"),
        ("/proc/stat", "stat"),
        ("/proc/uptime", "uptime"),
        ("/proc/version", "version"),
        ("/proc/vmstat", "vmstat"),
        ("/proc/sys/kernel/cap_last_cap", "sysctl_entry_cap_last_cap"),
        ("/proc/sys/fs/inotify/max_user_watches", "sysctl_inotify_max_user_watches"),
        ("/proc/sys/kernel/overflowuid", "sysctl_kernel_overflowuid"),
        ("/proc/sys/kernel/overflowgid", "sysctl_kernel_overflowgid"),
    ];
    for (real, fake) in pairs {
        let real_path = Path::new(real);
        let readable = File::open(real_path).map(|f| f.metadata().is_ok()).unwrap_or(false);
        if !readable {
            let fake_file = sysdata_dir.join(fake);
            if fake_file.exists() {
                binds.push(CString::new(format!(
                    "--bind={}:{}", fake_file.display(), real
                ))?);
            }
        }
    }
    Ok(binds)
}

// ==========================================================================
// Shell resolution
// ==========================================================================

pub(super) fn find_shell_in_rootfs(rootfs: &Path) -> Option<(&'static str, bool)> {
    let termux = has_termux_inside_container(rootfs);
    let candidates: Vec<(&'static str, bool)> = if termux {
        vec![
            ("/data/data/com.termux/files/usr/bin/bash", false),
            ("/data/data/com.termux/files/usr/bin/sh", false),
        ]
    } else {
        vec![
            ("/usr/bin/bash", false), ("/bin/bash", false),
            ("/usr/bin/sh", false),   ("/bin/sh", false),
            ("/usr/bin/dash", false), ("/bin/dash", false),
            ("/usr/bin/ash", false),  ("/bin/ash", false),
        ]
    };
    for (p, b) in candidates {
        if path_exists_in_rootfs(rootfs, p) { return Some((p, b)); }
    }
    if path_exists_in_rootfs(rootfs, "/usr/bin/busybox") {
        Some(("/usr/bin/busybox", true))
    } else if path_exists_in_rootfs(rootfs, "/bin/busybox") {
        Some(("/bin/busybox", true))
    } else { None }
}

fn build_native_args() -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let prefix_str = ctx.data_dir.join("usr").to_string_lossy().into_owned();
    let home_dir   = "/data/data/app.xodos2/files/home";
    let tmp_dir    = format!("{}/tmp", prefix_str);
    let _ = fs::create_dir_all(&tmp_dir);
    let _ = fs::create_dir_all(home_dir);

    #[cfg(target_pointer_width = "64")]
    let linker = "/system/bin/linker64";
    #[cfg(target_pointer_width = "32")]
    let linker = "/system/bin/linker";

    let bash = format!("{}/bin/bash", prefix_str);
    let sh   = format!("{}/bin/sh", prefix_str);
    let (shell, is_bionic) = if Path::new(&bash).exists() { (bash, true) }
        else if Path::new(&sh).exists() { (sh, true) }
        else { ("/system/bin/sh".to_string(), false) };

    let mut argv: Vec<CString> = Vec::new();
    if is_bionic { argv.push(CString::new(linker).unwrap()); }
    argv.push(CString::new(shell).unwrap());
    argv.push(CString::new("-l").unwrap());

    let env = vec![
        CString::new(format!("PREFIX={}", prefix_str)).unwrap(),
        CString::new(format!("HOME={}", home_dir)).unwrap(),
        CString::new(format!("TMPDIR={}", tmp_dir)).unwrap(),
        CString::new(format!("PATH={}/bin:/system/bin:/system/xbin", prefix_str)).unwrap(),
        CString::new(format!("LD_LIBRARY_PATH={}/lib", prefix_str)).unwrap(),
        CString::new("TERM=xterm-256color").unwrap(),
        CString::new("DISPLAY=:0").unwrap(),
        CString::new("PS1=[XoDos-Ark\\W]\\$ ").unwrap(),
    ];
    Ok((argv, env))
}

// ==========================================================================
// build_proot_args — full option support, DIRECT user switch
// ==========================================================================

fn build_proot_args(
    rootfs: &Path,
    shell_binary: &str,
    is_busybox: bool,
    opts: &ProotOptions,
) -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let (proot, loader) = proot_and_loader_paths()?;
    let termux_detected = has_termux_inside_container(rootfs);

    let effective_user = if opts.username.trim().is_empty() || opts.username.trim() == "root" {
        "root".to_string()
    } else {
        opts.username.trim().to_string()
    };
    let (target_uid, target_gid) = ensure_user_in_rootfs(rootfs, &effective_user)?;

    let mut argv: Vec<CString> = Vec::new();
    argv.push(CString::new(proot.to_string_lossy().as_bytes())?);
    ensure_fake_sysdata(rootfs)?;
    let sysdata_dir = rootfs.parent().context("rootfs parent")?.join("sysdata");

    argv.push(CString::new("-r").unwrap());
    argv.push(CString::new(rootfs.to_string_lossy().as_bytes())?);

    let kernel = if opts.kernel_release.trim().is_empty() {
        format!("{} {}", DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION)
    } else { opts.kernel_release.clone() };
    argv.push(CString::new(format!("--kernel-release={}", kernel)).unwrap());

    if !opts.no_link2symlink {
        argv.push(CString::new("-L").unwrap());
        argv.push(CString::new("--link2symlink").unwrap());
    }
    if !opts.no_sysvipc {
        argv.push(CString::new("--sysvipc").unwrap());
    }
    argv.push(CString::new("--kill-on-exit").unwrap());

    // ── CHANGE-ID: direct user switch, NO su ──
    if !opts.change_id.trim().is_empty() {
        // explicit override wins
        argv.push(CString::new(format!("--change-id={}", opts.change_id.trim())).unwrap());
        log::info!("proot: explicit --change-id={}", opts.change_id.trim());
    } else if termux_detected {
        let (u, g) = current_uid_gid();
        argv.push(CString::new(format!("--change-id={}:{}", u, g)).unwrap());
    } else if target_uid != 0 {
        // custom user → switch directly via fake_id0
        argv.push(CString::new(format!("--change-id={}:{}", target_uid, target_gid)).unwrap());
        log::info!("proot: direct user switch via --change-id={}:{}", target_uid, target_gid);
    } else {
        argv.push(CString::new("--change-id=0:0").unwrap());
    }

    // Hostname: fake /etc/hostname + /proc/sys/kernel/hostname + PS1 override
    let fake_hostname = write_fake_hostname(&ctx.data_dir, &opts.hostname);
    argv.push(CString::new(format!(
        "--bind={}:/etc/hostname", fake_hostname.display()
    ))?);
    argv.push(CString::new(format!(
        "--bind={}:/proc/sys/kernel/hostname", fake_hostname.display()
    ))?);
    inject_hostname_profile(rootfs, &opts.hostname);

    // Core binds
    argv.push(CString::new("--bind=/dev").unwrap());
    if !opts.minimal && !opts.isolated {
        argv.push(CString::new("--bind=/system").unwrap());
        argv.push(CString::new("--bind=/apex").unwrap());
        argv.push(CString::new("--bind=/storage").unwrap());
    }

    // ── HOME bind: only when shared_home=true ──
    if termux_detected {
        if opts.shared_home {
            let host_home = Path::new("/data/data/app.xodos2/files/home");
            if host_home.exists() {
                argv.push(CString::new(format!(
                    "--bind={}:/data/data/com.termux/files/home",
                    host_home.display()
                ))?);
            }
        }
    } else if opts.shared_home {
        let host_home = Path::new("/data/data/app.xodos2/files/home");
        let _ = fs::create_dir_all(host_home);
        let guest_home = if effective_user == "root" {
            "/root".to_string()
        } else {
            format!("/home/{}", effective_user)
        };
        argv.push(CString::new(format!(
            "--bind={}:{}", host_home.display(), guest_home
        ))?);
        log::info!("proot: shared home {} → {}", host_home.display(), guest_home);
    } else {
        log::info!("proot: shared_home=false — container's own home used");
    }

    argv.push(CString::new("--bind=/proc").unwrap());
    argv.push(CString::new("--bind=/sys").unwrap());

    // GPU
    if Path::new("/dev/kgsl-3d0").exists() {
        argv.push(CString::new("--bind=/dev/kgsl-3d0:/dev/kgsl-3d0").unwrap());
    }
    if Path::new("/dev/dri").exists() && File::open("/dev/dri").is_ok() {
        argv.push(CString::new("--bind=/dev/dri:/dev/dri").unwrap());
    }

    // Wayland/X11/VirGL/Pulse — only when not minimal
    if !opts.minimal {
        let wayland_rt = ctx.data_dir.join("usr/tmp");
        let _ = fs::create_dir_all(&wayland_rt);
        let _ = fs::set_permissions(&wayland_rt, PermissionsExt::from_mode(0o700));

        let host_x11 = ctx.data_dir.join("usr/tmp/.X11-unix");
        let guest_x11 = rootfs.join("tmp/.X11-unix");
        let _ = fs::create_dir_all(&host_x11);
        let _ = fs::create_dir_all(&guest_x11);
        let _ = fs::set_permissions(&host_x11, PermissionsExt::from_mode(0o1777));
        let _ = fs::set_permissions(&guest_x11, PermissionsExt::from_mode(0o1777));

        argv.push(CString::new(format!(
            "--bind={}:/run/user/0", wayland_rt.display()
        ))?);
        argv.push(CString::new(format!(
            "--bind={}:/tmp/.X11-unix", host_x11.display()
        ))?);

        let virgl_rt = ctx.data_dir.join("virgl-run");
        let _ = fs::create_dir_all(&virgl_rt);
        argv.push(CString::new(format!(
            "--bind={}:/run/xodos2-virgl", virgl_rt.display()
        ))?);

        let pulse_rt = host_pulse_runtime_dir(&ctx.data_dir);
        let _ = fs::create_dir_all(&pulse_rt);
        argv.push(CString::new(format!(
            "--bind={}:{}", pulse_rt.display(), GUEST_PULSE_RUNTIME_MOUNT
        ))?);
        let pulse_frag = write_pulse_guest_client_fragment(&ctx.data_dir);
        argv.push(CString::new(format!(
            "--bind={}:/etc/pulse/client.conf.d/99-xodos2-noshm.conf",
            pulse_frag.display()
        ))?);
        let profile_frag = write_guest_profile_fragment(&ctx.data_dir);
        argv.push(CString::new(format!(
            "--bind={}:/etc/profile.d/99-xodos2-runtime.sh",
            profile_frag.display()
        ))?);
    }

    // /dev/shm
    argv.push(CString::new(format!(
        "--bind={}/tmp:/dev/shm", rootfs.display()
    ))?);

    // FORCED shared /tmp
    let host_tmp = ctx.data_dir.join("usr/tmp");
    let _ = fs::create_dir_all(&host_tmp);
    let _ = fs::set_permissions(&host_tmp, PermissionsExt::from_mode(0o1777));
    argv.push(CString::new(format!("--bind={}:/tmp", host_tmp.display()))?);

    // SD card
    if !opts.isolated && !opts.minimal {
        if let Some(ref sd) = ctx.external_storage_path {
            if sd.exists() {
                for guest in ["/android", "/root/android", "/sdcard", "/root/sdcard"] {
                    argv.push(CString::new(format!(
                        "--bind={}:{}", sd.display(), guest
                    ))?);
                }
            }
        }
    }

    // Standard fds
    for (a, b) in [
        ("/dev/urandom", "/dev/random"),
        ("/proc/self/fd", "/dev/fd"),
        ("/proc/self/fd/0", "/dev/stdin"),
        ("/proc/self/fd/1", "/dev/stdout"),
        ("/proc/self/fd/2", "/dev/stderr"),
    ] {
        argv.push(CString::new(format!("--bind={}:{}", a, b)).unwrap());
    }

    // Fake proc + SELinux
    if !opts.minimal {
        for b in fake_proc_bindings(&sysdata_dir)? { argv.push(b); }
        argv.push(CString::new(format!(
            "--bind={}/sys/.empty:/sys/fs/selinux", rootfs.display()
        ))?);
    }

    // Extra Android dirs
    if !opts.isolated && !opts.minimal {
        for p in [
            "/vendor", "/odm", "/product", "/system_ext",
            "/linkerconfig/ld.config.txt", "/plat_property_contexts", "/property_contexts",
        ] {
            if Path::new(p).exists() {
                argv.push(CString::new(format!("--bind={}", p)).unwrap());
            }
        }
    }

    // User-supplied binds
    for b in &opts.binds {
        let b = b.trim();
        if b.is_empty() { continue; }
        argv.push(CString::new(format!("--bind={}", b))?);
    }

    // resolv.conf + hosts
    let resolv = rootfs.join("etc/resolv.conf");
    if !resolv.exists() {
        let _ = fs::create_dir_all(resolv.parent().unwrap_or(Path::new("/")));
        let _ = fs::write(&resolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
    }
    let hosts = rootfs.join("etc/hosts");
    if !hosts.exists() {
        let _ = fs::create_dir_all(hosts.parent().unwrap_or(Path::new("/")));
        let _ = fs::write(&hosts,
            "127.0.0.1 localhost.localdomain localhost\n\
             ::1 localhost.localdomain localhost ip6-localhost ip6-loopback\n");
    }

    // Working directory
    let default_cwd = if effective_user == "root" {
        "/root".to_string()
    } else {
        format!("/home/{}", effective_user)
    };
    let cwd = if opts.cwd.trim().is_empty() { default_cwd } else { opts.cwd.trim().to_string() };
    argv.push(CString::new(format!("--cwd={}", cwd))?);

    // ── Shell exec: same shell regardless of user; --change-id already switched identity ──
    if is_busybox {
        argv.push(CString::new(shell_binary).unwrap());
        argv.push(CString::new("sh").unwrap());
        argv.push(CString::new("-i").unwrap());
    } else {
        argv.push(CString::new(shell_binary).unwrap());
        argv.push(CString::new("-l").unwrap());
        argv.push(CString::new("-i").unwrap());
    }

    // ── Env vars ──
    let mut env: Vec<CString> = Vec::new();
    env.push(CString::new(format!("PROOT_LOADER={}", loader.to_string_lossy()))?);
    env.push(CString::new(format!("PROOT_TMP_DIR={}", ctx.cache_dir.display()))?);

    let home_dir = if termux_detected {
        "/data/data/com.termux/files/home".to_string()
    } else if effective_user == "root" {
        "/root".to_string()
    } else {
        format!("/home/{}", effective_user)
    };
    env.push(CString::new(format!("HOME={}", home_dir))?);
    env.push(CString::new(format!("USER={}", effective_user))?);
    env.push(CString::new(format!("LOGNAME={}", effective_user))?);
    env.push(CString::new(format!("HOSTNAME={}", opts.hostname.trim()))?);

    env.push(CString::new(format!(
        "PATH={}", if termux_detected {
            "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/sbin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin"
        } else {
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/data/data/app.xodos2/files/usr/bin"
        }
    ))?);

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
    env.push(CString::new(format!("PULSE_SERVER={}", guest_pulse_server_env()))?);

    if rootfs.join("nix/store").is_dir() {
        env.push(CString::new("ENV=/root/.bashrc").unwrap());
    }

    // User-supplied envs
    for e in &opts.envs {
        let e = e.trim();
        if e.is_empty() || !e.contains('=') { continue; }
        env.push(CString::new(e)?);
    }

    Ok((argv, env))
}

// ==========================================================================
// Dispatcher
// ==========================================================================

pub(super) fn build_exec_args(
    rootfs: &Path,
    opts: &ProotOptions,
) -> Result<(Vec<CString>, Vec<CString>)> {
    if is_proot_compatible(rootfs) {
        if let Some((shell, busybox)) = find_shell_in_rootfs(rootfs) {
            return build_proot_args(rootfs, shell, busybox, opts);
        }
    }
    build_native_args()
}

pub struct ChildProcess { pub pid: Pid }
impl Drop for ChildProcess {
    fn drop(&mut self) {
        let _ = nix::sys::signal::kill(self.pid, nix::sys::signal::Signal::SIGTERM);
    }
}

fn fork_pty_with_argv(
    argv: Vec<CString>, env: Vec<CString>, rows: u16, cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let a: Vec<&CStr> = argv.iter().map(|s| s.as_c_str()).collect();
    let e: Vec<&CStr> = env.iter().map(|s| s.as_c_str()).collect();
    let ws = Winsize { ws_row: rows.max(1), ws_col: cols.max(1), ws_xpixel: 0, ws_ypixel: 0 };
    let r = unsafe { forkpty(Some(&ws), None).context("forkpty failed")? };
    match r {
        ForkptyResult::Child => {
            if execve(argv[0].as_c_str(), &a, &e).is_err() {
                unsafe { nix::libc::_exit(1) };
            }
            unreachable!();
        }
        ForkptyResult::Parent { child, master } => {
            let rd_fd = dup(&master).context("dup")?.into_raw_fd();
            let wr_fd = master.into_raw_fd();
            let rd = unsafe { File::from_raw_fd(rd_fd) };
            let wr = unsafe { File::from_raw_fd(wr_fd) };
            Ok((ChildProcess { pid: child }, rd, Box::new(wr), wr_fd))
        }
    }
}

pub fn fork_pty_shell_in_rootfs(
    rootfs: &Path, rows: u16, cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let opts = load_proot_options_for(rootfs);
    let (argv, env) = build_exec_args(rootfs, &opts)?;
    fork_pty_with_argv(argv, env, rows, cols)
}

pub fn fork_pty_native_shell(
    rows: u16, cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let (argv, env) = build_native_args()?;
    fork_pty_with_argv(argv, env, rows, cols)
}

pub(super) fn path_exists_in_rootfs(rootfs: &Path, rel: &str) -> bool {
    let full = rootfs.join(rel.trim_start_matches('/'));
    if full.exists() { return true; }
    if full.is_symlink() {
        if let Ok(t) = fs::read_link(&full) {
            let r = if t.is_relative() {
                full.parent().unwrap_or(Path::new("/")).join(t)
            } else {
                let ts = t.to_string_lossy();
                rootfs.join(if ts.starts_with('/') { &ts[1..] } else { ts.as_ref() })
            };
            return r.exists();
        }
    }
    false
}