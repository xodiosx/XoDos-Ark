//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.
//!
//! A rootfs is considered **proot‑compatible** only when **all** of these exist:
//! * `.xodos2_rootfs_ok` sentinel
//! * `etc/os-release` or ( `usr/bin` and `root` ) – broad compatibility
//! * `sys/.empty` directory
//! If any of them is missing, the container is treated as non‑proot and a
//! lightweight Android shell is launched with `PREFIX` pointing to its `/usr`.

use super::{get_application_context, has_rootfs};
use super::{host_pulse_runtime_dir, guest_pulse_server_env, GUEST_PULSE_RUNTIME_MOUNT};
use anyhow::{Context, Result};
use nix::pty::{forkpty, ForkptyResult, Winsize};
use nix::unistd::{dup, execve, Pid};
use std::ffi::CString;
use std::fs::{self, File};
use std::io::Write;
use std::os::fd::IntoRawFd;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::{FromRawFd, RawFd};
use std::path::{Path, PathBuf};

// --------------------------------------------------------------------------
// Constants – match latest proot‑distro (Python version)
// --------------------------------------------------------------------------

const DEFAULT_FAKE_KERNEL_RELEASE: &str = "6.17.0-PRoot-Distro";
const DEFAULT_FAKE_KERNEL_VERSION: &str =
    "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000";

// --------------------------------------------------------------------------
// Pulse / profile helpers (unchanged)
// --------------------------------------------------------------------------

const PULSE_CLIENT_NO_SHM: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Host Pulse uses socket IPC only (no memfd across namespaces).\n\
enable-shm = no\n\
enable-memfd = no\n\
";

const GUEST_PROFILE_XODOS2_RUNTIME: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Keep essential runtime defaults across `su - user` (login shells read /etc/profile.d).\n\
# Only sets defaults when variables are unset; user exports remain authoritative.\n\
\n\
: \"${XDG_RUNTIME_DIR:=/run/user/0}\"\n\
: \"${PULSE_SERVER:=unix:/run/xodos2-pulse/native}\"\n\
export XDG_RUNTIME_DIR PULSE_SERVER\n\
\n\
# Start system D‑Bus if not already running (critical for Xfce & wallpaper)\n\
if ! pgrep -x dbus-daemon >/dev/null 2>&1; then\n\
    dbus-daemon --system --fork 2>/dev/null || true\n\
    if command -v dbus-launch >/dev/null 2>&1; then\n\
        eval \"$(dbus-launch --sh-syntax)\" 2>/dev/null || true\n\
        export DBUS_SESSION_BUS_ADDRESS\n\
    fi\n\
fi\n\
\n\
# Best-effort: set default sink if server becomes reachable.\n\
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
    Ok((proot, loader))
}

// --------------------------------------------------------------------------
// Compatibility check (broadened for non‑standard distros)
// --------------------------------------------------------------------------

fn is_proot_compatible(rootfs: &Path) -> bool {
    has_rootfs(rootfs)
        && rootfs.join("sys/.empty").is_dir()
        && (rootfs.join("etc/os-release").exists()
            || (rootfs.join("usr/bin").is_dir() && rootfs.join("root").is_dir()))
}

// --------------------------------------------------------------------------
// Fake /proc and /sys content (exact match to latest proot‑distro)
// --------------------------------------------------------------------------

/// Writes fake /proc and /sys stubs into a `sysdata/` directory next to `rootfs`,
/// exactly as the Python proot‑distro script does.  Also ensures `/sys/.empty`
/// exists inside the rootfs.
///
/// The fake files are placed **outside** the rootfs so they are removed when the
/// container is deleted, and we bind‑mount them read‑only into the guest.
fn ensure_fake_sysdata(rootfs: &Path) -> Result<()> {
    let sysdata_dir = rootfs
        .parent()
        .context("rootfs parent directory")?
        .join("sysdata");
    fs::create_dir_all(&sysdata_dir)?;
    fs::set_permissions(&sysdata_dir, PermissionsExt::from_mode(0o700))?;

    // Also create `/sys/.empty` inside the rootfs (for selinux fake).
    let sys_empty = rootfs.join("sys/.empty");
    fs::create_dir_all(&sys_empty)?;

    let write_if_missing = |path: &Path, content: &str| -> Result<()> {
        if !path.exists() {
            fs::write(path, content)?;
        }
        Ok(())
    };

    // ---- fake /proc files ----
    write_if_missing(&sysdata_dir.join("loadavg"), "0.12 0.07 0.02 2/165 765\n")?;

    write_if_missing(
        &sysdata_dir.join("stat"),
        r#"cpu  1957 0 2877 93280 262 342 254 87 0 0
cpu0 31 0 226 12027 82 10 4 9 0 0
cpu1 45 0 664 11144 21 263 233 12 0 0
cpu2 494 0 537 11283 27 10 3 8 0 0
cpu3 359 0 234 11723 24 26 5 7 0 0
cpu4 295 0 268 11772 10 12 2 12 0 0
cpu5 270 0 251 11833 15 3 1 10 0 0
cpu6 430 0 520 11386 30 8 1 12 0 0
cpu7 30 0 172 12108 50 8 1 13 0 0
intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
ctxt 140223
btime 1680020856
processes 772
procs_running 2
procs_blocked 0
softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
"#,
    )?;

    write_if_missing(&sysdata_dir.join("uptime"), "124.08 932.80\n")?;

    let fake_version = format!(
        "Linux version {} (proot@xodos2) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) {}\n",
        DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION
    );
    write_if_missing(&sysdata_dir.join("version"), &fake_version)?;

    write_if_missing(
        &sysdata_dir.join("vmstat"),
        r#"nr_free_pages 1743136
nr_zone_inactive_anon 179281
nr_zone_active_anon 7183
nr_zone_inactive_file 22858
nr_zone_active_file 51328
nr_zone_unevictable 642
nr_zone_write_pending 0
nr_mlock 0
nr_bounce 0
nr_zspages 0
nr_free_cma 0
numa_hit 1259626
numa_miss 0
numa_foreign 0
numa_interleave 720
numa_local 1259626
numa_other 0
nr_inactive_anon 179281
nr_active_anon 7183
nr_inactive_file 22858
nr_active_file 51328
nr_unevictable 642
nr_slab_reclaimable 8091
nr_slab_unreclaimable 7804
nr_isolated_anon 0
nr_isolated_file 0
workingset_nodes 0
workingset_refault_anon 0
workingset_refault_file 0
workingset_activate_anon 0
workingset_activate_file 0
workingset_restore_anon 0
workingset_restore_file 0
workingset_nodereclaim 0
nr_anon_pages 7723
nr_mapped 8905
nr_file_pages 253569
nr_dirty 0
nr_writeback 0
nr_writeback_temp 0
nr_shmem 178741
nr_shmem_hugepages 0
nr_shmem_pmdmapped 0
nr_file_hugepages 0
nr_file_pmdmapped 0
nr_anon_transparent_hugepages 1
nr_vmscan_write 0
nr_vmscan_immediate_reclaim 0
nr_dirtied 0
nr_written 0
nr_throttled_written 0
nr_kernel_misc_reclaimable 0
nr_foll_pin_acquired 0
nr_foll_pin_released 0
nr_kernel_stack 2780
nr_page_table_pages 344
nr_sec_page_table_pages 0
nr_swapcached 0
pgpromote_success 0
pgpromote_candidate 0
nr_dirty_threshold 356564
nr_dirty_background_threshold 178064
pgpgin 890508
pgpgout 0
pswpin 0
pswpout 0
pgalloc_dma 272
pgalloc_dma32 261
pgalloc_normal 1328079
pgalloc_movable 0
pgalloc_device 0
allocstall_dma 0
allocstall_dma32 0
allocstall_normal 0
allocstall_movable 0
allocstall_device 0
pgskip_dma 0
pgskip_dma32 0
pgskip_normal 0
pgskip_movable 0
pgskip_device 0
pgfree 3077011
pgactivate 0
pgdeactivate 0
pglazyfree 0
pgfault 176973
pgmajfault 488
pglazyfreed 0
pgrefill 0
pgreuse 19230
pgsteal_kswapd 0
pgsteal_direct 0
pgsteal_khugepaged 0
pgdemote_kswapd 0
pgdemote_direct 0
pgdemote_khugepaged 0
pgscan_kswapd 0
pgscan_direct 0
pgscan_khugepaged 0
pgscan_direct_throttle 0
pgscan_anon 0
pgscan_file 0
pgsteal_anon 0
pgsteal_file 0
zone_reclaim_failed 0
pginodesteal 0
slabs_scanned 0
kswapd_inodesteal 0
kswapd_low_wmark_hit_quickly 0
kswapd_high_wmark_hit_quickly 0
pageoutrun 0
pgrotated 0
drop_pagecache 0
drop_slab 0
oom_kill 0
numa_pte_updates 0
numa_huge_pte_updates 0
numa_hint_faults 0
numa_hint_faults_local 0
numa_pages_migrated 0
pgmigrate_success 0
pgmigrate_fail 0
thp_migration_success 0
thp_migration_fail 0
thp_migration_split 0
compact_migrate_scanned 0
compact_free_scanned 0
compact_isolated 0
compact_stall 0
compact_fail 0
compact_success 0
compact_daemon_wake 0
compact_daemon_migrate_scanned 0
compact_daemon_free_scanned 0
htlb_buddy_alloc_success 0
htlb_buddy_alloc_fail 0
cma_alloc_success 0
cma_alloc_fail 0
unevictable_pgs_culled 27002
unevictable_pgs_scanned 0
unevictable_pgs_rescued 744
unevictable_pgs_mlocked 744
unevictable_pgs_munlocked 744
unevictable_pgs_cleared 0
unevictable_pgs_stranded 0
thp_fault_alloc 13
thp_fault_fallback 0
thp_fault_fallback_charge 0
thp_collapse_alloc 4
thp_collapse_alloc_failed 0
thp_file_alloc 0
thp_file_fallback 0
thp_file_fallback_charge 0
thp_file_mapped 0
thp_split_page 0
thp_split_page_failed 0
thp_deferred_split_page 1
thp_split_pmd 1
thp_scan_exceed_none_pte 0
thp_scan_exceed_swap_pte 0
thp_scan_exceed_share_pte 0
thp_split_pud 0
thp_zero_page_alloc 0
thp_zero_page_alloc_failed 0
thp_swpout 0
thp_swpout_fallback 0
balloon_inflate 0
balloon_deflate 0
balloon_migrate 0
swap_ra 0
swap_ra_hit 0
ksm_swpin_copy 0
cow_ksm 0
zswpin 0
zswpout 0
direct_map_level2_splits 29
direct_map_level3_splits 0
nr_unstable 0
"#,
    )?;

    write_if_missing(
        &sysdata_dir.join("sysctl_entry_cap_last_cap"),
        "40\n",
    )?;
    write_if_missing(
        &sysdata_dir.join("sysctl_inotify_max_user_watches"),
        "4096\n",
    )?;
    write_if_missing(
        &sysdata_dir.join("sysctl_kernel_overflowuid"),
        "65534\n",
    )?;
    write_if_missing(
        &sysdata_dir.join("sysctl_kernel_overflowgid"),
        "65534\n",
    )?;

    Ok(())
}

/// Returns `--bind` arguments for fake /proc entries, but only for those paths
/// that are *not* readable on the host.  This exactly matches the Python
/// proot‑distro logic.
fn fake_proc_bindings(_rootfs: &Path, sysdata_dir: &Path) -> Result<Vec<CString>> {
    let mut binds = Vec::new();
    let pairs = [
        ("/proc/loadavg", "loadavg"),
        ("/proc/stat", "stat"),
        ("/proc/uptime", "uptime"),
        ("/proc/version", "version"),
        ("/proc/vmstat", "vmstat"),
        ("/proc/sys/kernel/cap_last_cap", "sysctl_entry_cap_last_cap"),
        (
            "/proc/sys/fs/inotify/max_user_watches",
            "sysctl_inotify_max_user_watches",
        ),
        (
            "/proc/sys/kernel/overflowuid",
            "sysctl_kernel_overflowuid",
        ),
        (
            "/proc/sys/kernel/overflowgid",
            "sysctl_kernel_overflowgid",
        ),
    ];

    for (real_path, fake_name) in pairs {
        let real = Path::new(real_path);
        let readable = fs::File::open(real).map(|f| f.metadata().is_ok()).unwrap_or(false);
        if !readable {
            let fake_file = sysdata_dir.join(fake_name);
            if fake_file.exists() {
                binds.push(
                    CString::new(format!("--bind={}:{}", fake_file.display(), real_path))
                        .context("bind fake proc")?,
                );
            } else {
                log::warn!(
                    "proot: fake file {:?} does not exist; skipping bind for {}",
                    fake_file,
                    real_path
                );
            }
        }
    }

    Ok(binds)
}

// --------------------------------------------------------------------------
// Argument builder (main logic)
// --------------------------------------------------------------------------

pub(super) fn build_exec_args(
    rootfs: &Path,
) -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let (proot, loader) = proot_and_loader_paths()?;
    let proot_str = proot.to_string_lossy();
    let loader_str = loader.to_string_lossy();

    let mut argv: Vec<CString> = vec![CString::new(proot_str.as_bytes()).context("proot path")?];

    if is_proot_compatible(rootfs) {
        // ---------- full PRoot container ----------

        // 0. Write fake /proc & /sys content (outside rootfs)
        ensure_fake_sysdata(rootfs)?;
        let sysdata_dir = rootfs
            .parent()
            .context("rootfs parent")?
            .join("sysdata");

        // 1. Basic rootfs and options
        argv.push(CString::new("-r").unwrap());
        argv.push(CString::new(rootfs.to_string_lossy().as_bytes()).context("rootfs path")?);

        // Fake kernel version (critical for glibc & dbus)
        // Fake kernel version (critical for glibc & dbus)
let kernel_release = format!("{} {}", DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION);
argv.push(CString::new(format!("--kernel-release={}", kernel_release)).unwrap());

        argv.push(CString::new("-L").unwrap());           // follow symlinks
        argv.push(CString::new("--link2symlink").unwrap());
        argv.push(CString::new("--sysvipc").unwrap());
        argv.push(CString::new("--kill-on-exit").unwrap());
        argv.push(CString::new("--root-id").unwrap());

        // 2. Core Android bindings (always needed)
        argv.push(CString::new("--bind=/dev").unwrap());
        argv.push(CString::new("--bind=/data").unwrap());
        argv.push(CString::new("--bind=/proc").unwrap());
        argv.push(CString::new("--bind=/sys").unwrap());
        argv.push(CString::new("--bind=/system").unwrap());
        argv.push(CString::new("--bind=/apex").unwrap());
        argv.push(CString::new("--bind=/storage").unwrap());

        // 3. GPU / DRM devices
        if Path::new("/dev/kgsl-3d0").exists() {
            argv.push(CString::new("--bind=/dev/kgsl-3d0:/dev/kgsl-3d0").unwrap());
        }
        if Path::new("/dev/dri").exists() && File::open("/dev/dri").is_ok() {
            argv.push(CString::new("--bind=/dev/dri:/dev/dri").unwrap());
        } else {
            log::info!("proot: skip bind /dev/dri (missing or not accessible)");
            // fallback: use a dummy directory to avoid errors
            let dummy_dri = ctx.data_dir.join("usr").join("tmp");
            fs::create_dir_all(&dummy_dri)?;
            argv.push(CString::new(format!("--bind={}:/dev/dri", dummy_dri.display())).unwrap());
        }

        // 4. Wayland runtime and X11 socket
        let wayland_runtime = ctx.data_dir.join("usr").join("tmp");
        fs::create_dir_all(&wayland_runtime)?;
        fs::set_permissions(&wayland_runtime, PermissionsExt::from_mode(0o700))?;

        let host_x11_dir = ctx.data_dir.join("tmp").join(".X11-unix");
        fs::create_dir_all(&host_x11_dir)?;
        let guest_x11_dir = rootfs.join("tmp/.X11-unix");
        fs::create_dir_all(&guest_x11_dir)?;
        fs::set_permissions(&host_x11_dir, PermissionsExt::from_mode(0o1777))?;
        fs::set_permissions(&guest_x11_dir, PermissionsExt::from_mode(0o1777))?;

        argv.push(CString::new(format!("--bind={}:/run/user/0", wayland_runtime.display())).unwrap());
        argv.push(
            CString::new(format!("--bind={}:{}", host_x11_dir.display(), "/tmp/.X11-unix"))
                .context("x11 unix socket bind")?,
        );

        // 5. Virgl / GPU acceleration
        let virgl_runtime = ctx.data_dir.join("virgl-run");
        fs::create_dir_all(&virgl_runtime)?;
        argv.push(
            CString::new(format!("--bind={}:/run/xodos2-virgl", virgl_runtime.display()))
                .context("virgl runtime bind")?,
        );

        // 6. PulseAudio
        let pulse_rt = host_pulse_runtime_dir(&ctx.data_dir);
        fs::create_dir_all(&pulse_rt)?;
        argv.push(
            CString::new(format!("--bind={}:{}", pulse_rt.display(), GUEST_PULSE_RUNTIME_MOUNT))
                .context("pulse runtime bind")?,
        );
        let pulse_client_frag = write_pulse_guest_client_fragment(&ctx.data_dir);
        argv.push(
            CString::new(format!(
                "--bind={}:/etc/pulse/client.conf.d/99-xodos2-noshm.conf",
                pulse_client_frag.display()
            ))
            .context("pulse client no-shm bind")?,
        );
        let profile_frag = write_guest_profile_fragment(&ctx.data_dir);
        argv.push(
            CString::new(format!(
                "--bind={}:/etc/profile.d/99-xodos2-runtime.sh",
                profile_frag.display()
            ))
            .context("profile.d runtime bind")?,
        );

        // 7. /tmp → /dev/shm (POSIX shared memory)
        argv.push(CString::new(format!("--bind={}/tmp:/dev/shm", rootfs.display())).unwrap());

        // 8. SD card / external storage
        if let Some(ref sdcard) = ctx.external_storage_path {
            if sdcard.exists() {
                for (guest, host) in [
                    ("/android", sdcard),
                    ("/root/android", sdcard),
                    ("/sdcard", sdcard),
                    ("/root/sdcard", sdcard),
                ] {
                    argv.push(CString::new(format!("--bind={}:{}", host.display(), guest)).unwrap());
                }
            }
        }

        // 9. Standard /dev and /proc bindings
        argv.push(CString::new("--bind=/dev/urandom:/dev/random").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd:/dev/fd").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/0:/dev/stdin").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/1:/dev/stdout").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/2:/dev/stderr").unwrap());

        // 10. Fake /proc entries – bind only if real file is not readable
        let fake_binds = fake_proc_bindings(rootfs, &sysdata_dir)?;
        argv.extend(fake_binds);

        // 11. Fake SELinux
        argv.push(
            CString::new(format!("--bind={}/sys/.empty:/sys/fs/selinux", rootfs.display())).unwrap(),
        );

        // 12. Extra Android system directories (conditionally)
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

        // 13. Bind Termux prefix (useful for host tools)
        if Path::new("/data/data/com.xodos//files/usr").exists() {
            argv.push(CString::new("--bind=/data/data/com.xodos//files/usr").unwrap());
        }

        // 14. Ensure basic /etc files exist
        let resolv_conf = rootfs.join("etc/resolv.conf");
        if !resolv_conf.exists() {
            fs::write(
                &resolv_conf,
                "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
            )?;
        }
        let hosts = rootfs.join("etc/hosts");
        if !hosts.exists() {
            fs::write(
                &hosts,
                "127.0.0.1 localhost.localdomain localhost\n::1 localhost.localdomain localhost ip6-localhost ip6-loopback\n",
            )?;
        }

        // 15. Shell detection (unchanged)
        let standard_shells: &[&str] = &[
            "bin/bash", "usr/bin/bash",
            "bin/sh", "usr/bin/sh",
            "bin/dash", "usr/bin/dash",
            "bin/ash", "usr/bin/ash",
        ];

        let shell_info = standard_shells
            .iter()
            .find(|c| path_exists_in_rootfs(rootfs, c))
            .map(|&path| {
                let binary = path.strip_prefix("usr/").unwrap_or(path);
                (binary, binary)
            })
            .or_else(|| {
                if path_exists_in_rootfs(rootfs, "bin/busybox")
                    || path_exists_in_rootfs(rootfs, "usr/bin/busybox")
                {
                    Some(("busybox", "sh"))
                } else {
                    None
                }
            });

        let (binary, applet) = shell_info
            .ok_or_else(|| anyhow::anyhow!("no usable shell found in rootfs"))?;

        if binary == "busybox" {
            argv.push(CString::new("/bin/busybox").unwrap());
            argv.push(CString::new(applet).unwrap());
            argv.push(CString::new("-i").unwrap());
        } else {
            argv.push(CString::new(format!("/{}", binary)).unwrap());
            argv.push(CString::new("-l").unwrap());
            argv.push(CString::new("-i").unwrap());
        }
    } else {
        // ---------- fallback: Android system shell with container prefix ----------
        argv.push(CString::new("/system/bin/sh").unwrap());
        argv.push(CString::new("-c").unwrap());

        let prefix = ctx.data_dir.join("usr");
        let prefix_str = prefix.to_string_lossy().into_owned();
        let safe_home = "/data/data/app.xodos2/files/";
        let tmp_dir = format!("{}/tmp", prefix_str);

        let fallback_script = format!(
            "export PREFIX='{prefix}'; \
             export DISPLAY=:0; \
             export PATH=$PREFIX/bin:$PATH; \
             export LD_LIBRARY_PATH=$PREFIX/lib:$LD_LIBRARY_PATH; \
             export TMPDIR='{tmp}'; \
             export HOME='{home}'; \
             mkdir -p \"{tmp}\" \"{home}\" 2>/dev/null; \
             if [ -d \"$PREFIX\" ]; then cd \"$PREFIX\" || true; else cd \"{home}\" || true; fi; \
             if [ -f \"$PREFIX/bin/bash\" ]; then exec bash -l || true; else exec sh -i || true; fi; \
             export PS1='[XoDos-Ark\\W]# '; \
             ",
            prefix = prefix_str,
            tmp = tmp_dir,
            home = safe_home,
        );
        argv.push(CString::new(fallback_script.as_bytes()).context("fallback script")?);
    }

    // --------------------------------------------------------------------------
    // Environment (common for both modes)
    // --------------------------------------------------------------------------
    let mut env: Vec<CString> = vec![
        CString::new(format!("PROOT_LOADER={}", loader_str)).unwrap(),
        CString::new(format!("PROOT_TMP_DIR={}", ctx.cache_dir.display())).unwrap(),
        CString::new("HOME=/root").unwrap(),
        CString::new("TERM=xterm-256color").unwrap(),
        CString::new("LANG=C.UTF-8").unwrap(),
        CString::new(
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games:/system/bin:/system/xbin",
        )
        .unwrap(),
        CString::new("TMPDIR=/tmp").unwrap(),
        CString::new("XDG_RUNTIME_DIR=/run/user/0").unwrap(),
        CString::new("WAYLAND_DISPLAY=wayland-xodos2").unwrap(),
        CString::new("XDG_SESSION_TYPE=wayland").unwrap(),
        CString::new("QT_QPA_PLATFORM=wayland").unwrap(),
    ];
    env.push(CString::new("QT_QUICK_BACKEND=software").unwrap());
    env.push(CString::new("VTEST_SOCKET_NAME=/run/xodos2-virgl/vtest.sock").unwrap());
    env.push(CString::new("VTEST_RENDERER_SOCKET_NAME=/run/xodos2-virgl/vtest.sock").unwrap());

    // --------------------------------------------------------------------------
    // proot‑compatible environment additions
    // --------------------------------------------------------------------------
    if is_proot_compatible(rootfs) {
        // Android environment variables (from host)
        let android_vars = [
            "ANDROID_ART_ROOT",
            "ANDROID_DATA",
            "ANDROID_I18N_ROOT",
            "ANDROID_ROOT",
            "ANDROID_RUNTIME_ROOT",
            "ANDROID_TZDATA_ROOT",
            "BOOTCLASSPATH",
            "DEX2OATBOOTCLASSPATH",
            "EXTERNAL_STORAGE",
        ];
        for &var in &android_vars {
            if let Ok(val) = std::env::var(var) {
                env.push(CString::new(format!("{}={}", var, val)).context(var)?);
            }
        }

        env.push(CString::new("USER=root").unwrap());
        env.push(CString::new("LOGNAME=root").unwrap());
        env.push(
            CString::new(format!("PULSE_SERVER={}", guest_pulse_server_env()))
                .context("PULSE_SERVER")?,
        );
        env.push(CString::new("XWAYLAND_NO_GLAMOR=1").unwrap());
        env.push(CString::new("MOZ_FAKE_NO_SANDBOX=1").unwrap());

        // Nix support (unchanged)
        if rootfs.join("nix/store").is_dir() {
            if let Some(pos) = env.iter().position(|s| {
                s.to_str().map_or(false, |v| v.starts_with("PATH="))
            }) {
                let current_path = env[pos].to_str().unwrap_or("PATH=").to_string();
                let new_path = format!(
                    "PATH=/root/.nix-profile/bin:/run/current-system/sw/bin:{}",
                    &current_path[5..]
                );
                env[pos] = CString::new(new_path).unwrap();
            }
            env.push(CString::new("ENV=/root/.bashrc").unwrap());
            env.push(
                CString::new(
                    "NIX_PATH=/root/.nix-defexpr/channels:/nix/var/nix/profiles/per-user/root/channels",
                )
                .unwrap(),
            );
            env.push(
                CString::new(
                    "MANPATH=/root/.nix-profile/share/man:/run/current-system/sw/share/man:$MANPATH",
                )
                .unwrap(),
            );
        }
    }

    Ok((argv, env))
}

// --------------------------------------------------------------------------
// PTY shell spawn (unchanged)
// --------------------------------------------------------------------------

pub struct ChildProcess {
    pub pid: Pid,
}

impl Drop for ChildProcess {
    fn drop(&mut self) {
        let _ = nix::sys::signal::kill(self.pid, nix::sys::signal::Signal::SIGTERM);
    }
}

pub fn fork_pty_shell_in_rootfs(
    rootfs: &Path,
    initial_rows: u16,
    initial_cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let (argv, env) = build_exec_args(rootfs)?;

    let argv_refs: Vec<&std::ffi::CStr> = argv.iter().map(|s| s.as_c_str()).collect();
    let env_refs: Vec<&std::ffi::CStr> = env.iter().map(|s| s.as_c_str()).collect();

    let winsize = Winsize {
        ws_row: initial_rows.max(1),
        ws_col: initial_cols.max(1),
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    let result = unsafe { forkpty(Some(&winsize), None).context("forkpty failed")? };

    match result {
        ForkptyResult::Child => {
            if execve(argv[0].as_c_str(), &argv_refs, &env_refs).is_err() {
                unsafe { nix::libc::_exit(1) };
            }
            unreachable!();
        }
        ForkptyResult::Parent { child, master } => {
            let master_read_fd = dup(&master).context("dup master for read")?.into_raw_fd();
            let master_write_fd = master.into_raw_fd();
            let master_read = unsafe { File::from_raw_fd(master_read_fd) };
            let master_write = unsafe { File::from_raw_fd(master_write_fd) };
            let stdin: Box<dyn Write + Send> = Box::new(master_write);
            Ok((ChildProcess { pid: child }, master_read, stdin, master_write_fd))
        }
    }
}

/// Returns true if `relative_path` exists inside the rootfs, correctly following
/// symlinks that point to absolute paths relative to the rootfs itself.
pub(super) fn path_exists_in_rootfs(rootfs: &Path, relative_path: &str) -> bool {
    let full_path = rootfs.join(relative_path);
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