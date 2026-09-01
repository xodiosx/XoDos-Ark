//! Download tarball, extract to staging, write [`ROOTFS_READY_SENTINEL`], rename into place.
//!
//! # Download
//! Stream download, rename into place. Checksum validation disabled for dynamic archives.
//!
//! # Extract
//! Tar extract, placeholder proc/sys, structure checks, and hard-link bypass for unprivileged Android storage.

use super::{
    get_application_context, has_rootfs, ROOTFS_READY_SENTINEL,
};
use anyhow::{Context, Result};
use std::collections::HashSet;
use std::fs::File;
use std::io::{BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use flate2::read::GzDecoder;
use std::os::unix::fs::PermissionsExt;

static DOWNLOAD_LOCK: Mutex<()> = Mutex::new(());

pub type ProgressFn = Box<dyn Fn(u32, &str) + Send>;

// ---------------------------------------------------------------------------
// download helpers
// ---------------------------------------------------------------------------

fn download_tarball_with_progress<F>(
    tmp_path: &Path,
    final_path: &Path,
    pct_min: u32,
    pct_max: u32,
    report: &F,
    url: &str,
    label: &str,
) -> Result<()>
where
    F: Fn(u32, &str),
{
    // 1. Sanitize the URL to remove accidental newlines, spaces, or JNI null bytes
    let clean_url = url.trim_matches(|c: char| c.is_whitespace() || c == '\0');

    // 2. Add a standard User-Agent so Cloudflare/GitHub/Mirrors don't block the request
    let client = reqwest::blocking::Client::builder()
        .user_agent("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        .timeout(std::time::Duration::from_secs(3600))
        .build()
        .context("create HTTP client")?;

    let mut response = client.get(clean_url).send().context("download request")?;

    if !response.status().is_success() {
        anyhow::bail!(
            "download failed: {} {} (URL: {})",
            response.status(),
            response.text().unwrap_or_default(),
            clean_url
        );
    }

    let total = response.content_length().unwrap_or(0);
    let mut file = File::create(tmp_path).context("create temp file")?;
    let mut downloaded: u64 = 0;
    let mut buf = [0u8; 8192];
    let mut last_reported_pct = 0u8;
    let mut last_reported_bytes: u64 = 0;

    loop {
        let n = response.read(&mut buf).context("read response")?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n]).context("write file")?;
        downloaded += n as u64;

        if total > 0 {
            let percent = (downloaded * 100 / total).min(100) as u8;
            if percent != last_reported_pct {
                let downloaded_mb = downloaded as f64 / 1024.0 / 1024.0;
                let total_mb = total as f64 / 1024.0 / 1024.0;
                let msg = format!("{label} {percent}% ({downloaded_mb:.2} MB / {total_mb:.2} MB)");
                let pct = pct_min + ((percent as u32) * (pct_max - pct_min) / 100);
                report(pct, &msg);
                last_reported_pct = percent;
            }
        } else {
            const STEP_BYTES: u64 = 2 * 1024 * 1024;
            if downloaded.saturating_sub(last_reported_bytes) >= STEP_BYTES {
                last_reported_bytes = downloaded;
                let downloaded_mb = downloaded as f64 / 1024.0 / 1024.0;
                let pct = (pct_min + 1).min(pct_max.saturating_sub(1));
                report(pct, &format!("{label} ({downloaded_mb:.2} MB)"));
            }
        }
    }

    report(pct_max, &format!("{label} 100%"));
    file.flush().context("flush temp file")?;
    drop(file);

    if std::fs::rename(tmp_path, final_path).is_err() {
        std::fs::copy(tmp_path, final_path).context("copy temp to final")?;
        let _ = std::fs::remove_file(tmp_path);
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// extract helpers
// ---------------------------------------------------------------------------

fn validate_rootfs_structure(rootfs_path: &Path) -> Result<()> {
    let shell_candidates = [
        "bin/sh", "usr/bin/sh",
        "bin/bash", "usr/bin/bash",
        "bin/dash", "usr/bin/dash",
        "bin/ash", "usr/bin/ash",
        "bin/busybox", "usr/bin/busybox",
    ];
    let has_shell = shell_candidates.iter().any(|p| rootfs_path.join(p).exists());

    let has_exec = if !has_shell {
        let check_dir = |dir: &str| -> bool {
            let d = rootfs_path.join(dir);
            if d.is_dir() {
                std::fs::read_dir(&d).map_or(false, |rd| {
                    rd.filter_map(|e| e.ok())
                        .any(|e| e.file_type().map_or(false, |t| t.is_file()) && !e.file_name().to_string_lossy().starts_with('.'))
                })
            } else { false }
        };
        check_dir("bin") || check_dir("usr/bin")
    } else { true };

    let has_directory_structure = if !has_shell && !has_exec {
        let essential_dirs = ["usr", "var", "etc"];
        essential_dirs.iter().all(|d| rootfs_path.join(d).is_dir())
    } else { false };

    anyhow::ensure!(
        has_shell || has_exec || has_directory_structure,
        "no usable shell or executable found, and missing core directories (/usr, /var, /etc)"
    );

    anyhow::ensure!(rootfs_path.join("proc").is_dir(), "missing proc/");

    Ok(())
}

/// Recursively ensures all extracted files and directories have standard permissions.
/// Directories: 755, regular files: 644 (or 755 if executable bit originally set).
fn fix_permissions_recursive(path: &Path) {
    if let Ok(metadata) = std::fs::symlink_metadata(path) {
        let file_type = metadata.file_type();

        if file_type.is_dir() || file_type.is_file() {
            let mut perms = metadata.permissions();

            if file_type.is_dir() {
                perms.set_mode(0o755);
            } else {
                let original_mode = perms.mode();
                let is_executable = original_mode & 0o111 != 0;
                perms.set_mode(if is_executable { 0o755 } else { 0o644 });
            }

            let _ = std::fs::set_permissions(path, perms);
        }

        if file_type.is_dir() {
            if let Ok(entries) = std::fs::read_dir(path) {
                for entry in entries.flatten() {
                    fix_permissions_recursive(&entry.path());
                }
            }
        }
    }
}

/// Safely sanitizes an archive path to ensure it cannot escape the extraction directory.
/// Strips absolute roots and prevents `../` path traversal attacks.
fn sanitize_archive_path(path: &Path) -> Option<PathBuf> {
    let mut safe_path = PathBuf::new();

    for component in path.components() {
        match component {
            std::path::Component::Normal(c) => safe_path.push(c),
            std::path::Component::ParentDir => return None,
            _ => continue,
        }
    }

    if safe_path.as_os_str().is_empty() {
        None
    } else {
        Some(safe_path)
    }
}

/// Safely unpacks an archive, defers hard links, fixes permissions, and resolves links via multi-pass copies.
fn unpack_archive_safe<R: Read>(mut archive: tar::Archive<R>, temp_extract: &Path) -> Result<()> {
    let mut deferred_links: Vec<(PathBuf, PathBuf)> = Vec::new();

    // Pass 1: Extract everything except hard links.
    for entry_result in archive.entries().context("failed to read archive entries")? {
        let mut entry = match entry_result {
            Ok(e) => e,
            Err(err) => {
                log::warn!("Skipping a corrupted archive entry: {:?}", err);
                continue;
            }
        };

        let raw_path = match entry.path() {
            Ok(p) => p.into_owned(),
            Err(_) => continue,
        };

        let safe_entry_path = match sanitize_archive_path(&raw_path) {
            Some(p) => p,
            None => {
                log::warn!("Skipping unsafe entry path: {:?}", raw_path);
                continue;
            }
        };

        let dest_path = temp_extract.join(&safe_entry_path);
        let entry_type = entry.header().entry_type();

        // Unprivileged Android cannot create block/character devices or fifos. Skip them.
        if entry_type.is_block_special() || entry_type.is_character_special() || entry_type.is_fifo() {
            continue;
        }

        if entry_type.is_hard_link() {
            if let Ok(Some(link_name)) = entry.link_name() {
                if let Some(safe_link_path) = sanitize_archive_path(&link_name) {
                    let link_target = temp_extract.join(safe_link_path);
                    deferred_links.push((dest_path, link_target));
                } else {
                    log::warn!("Skipping unsafe hard-link target: {:?}", link_name);
                }
            }
        } else if entry_type.is_dir() {
            if let Err(e) = std::fs::create_dir_all(&dest_path) {
                log::warn!("Failed to create directory {:?}: {:?}", dest_path, e);
            }
        } else if entry_type.is_symlink() {
            if let Ok(Some(target)) = entry.link_name() {
                if let Some(parent) = dest_path.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                let _ = std::fs::remove_file(&dest_path);
                #[cfg(unix)]
                {
                    use std::os::unix::fs::symlink;
                    let _ = symlink(&target, &dest_path);
                }
            }
        } else {
            // Regular file: extract explicitly
            if let Some(parent) = dest_path.parent() {
                if let Err(e) = std::fs::create_dir_all(parent) {
                    log::warn!("Failed to create parent dir for file {:?}: {:?}", dest_path, e);
                    continue;
                }
            }
            let _ = std::fs::remove_file(&dest_path);
            match entry.unpack(&dest_path) {
                Ok(_) => {}
                Err(err) => {
                    log::warn!("Failed to unpack file {:?}: {:?}", dest_path, err);
                }
            }
        }
    }

    // Pass 2: Recursively fix permissions so Android can read everything we just dumped.
    fix_permissions_recursive(temp_extract);

    // Pass 3: Resolve all deferred hard links via physical file copies.
    // We iterate multiple times to handle chains of hard links.
    let mut resolved = HashSet::new();
    let mut remaining = deferred_links;
    let mut progress = true;

    while progress {
        progress = false;
        let mut next_remaining = Vec::new();

        for (dest_path, link_target) in remaining {
            if link_target.exists() {
                match std::fs::copy(&link_target, &dest_path) {
                    Ok(_) => {
                        if let Ok(meta) = std::fs::metadata(&link_target) {
                            let _ = std::fs::set_permissions(&dest_path, meta.permissions());
                        }
                        resolved.insert(dest_path.clone());
                        progress = true;
                    }
                    Err(copy_err) => {
                        log::warn!("Hard-link copy failed for {:?} -> {:?}: {:?}", link_target, dest_path, copy_err);
                        next_remaining.push((dest_path, link_target));
                    }
                }
            } else {
                // Target not yet created, try later.
                next_remaining.push((dest_path, link_target));
            }
        }

        remaining = next_remaining;
    }

    // If any hard links remain unresolved, fail extraction.
    if !remaining.is_empty() {
        anyhow::bail!(
            "Unresolved hard links after extraction: {} links could not be resolved",
            remaining.len()
        );
    }

    // After hard-link resolution, run permission fix again (directories may have been created
    // with default 700 during this phase).
    fix_permissions_recursive(temp_extract);

    Ok(())
}

fn extract_tarball(tarball_path: &Path, dest: &Path, temp_extract: &Path) -> Result<()> {
    let file = File::open(tarball_path).context("open tarball")?;

    std::fs::create_dir_all(temp_extract).context("create temp extract dir")?;

    let file_name = tarball_path
        .file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .to_lowercase();

    if file_name.ends_with(".tar.gz") || file_name.ends_with(".tgz") {
        let gz_decoder = GzDecoder::new(BufReader::new(file));
        let archive = tar::Archive::new(gz_decoder);
        unpack_archive_safe(archive, temp_extract).context("extract tar.gz tarball")?;
    } else {
        let xz_decoder = xz2::read::XzDecoder::new(BufReader::new(file));
        let archive = tar::Archive::new(xz_decoder);
        unpack_archive_safe(archive, temp_extract).context("extract tar.xz tarball")?;
    }

    // Helper to check if a directory looks like a rootfs
    let is_rootfs_dir = |dir: &Path| -> bool {
        dir.is_dir()
            && (dir.join("bin").is_dir()
                || dir.join("usr").is_dir()
                || dir.join("var").is_dir()
                || dir.join("root").is_dir()
                || dir.join("etc").is_dir())
    };

    // ---------------------------------------------------------------
    // Accept both flat (./usr ./bin ./etc) and wrapped (subdir/) tarballs
    // ---------------------------------------------------------------
    if is_rootfs_dir(temp_extract) {
        // Flat tarball – the temp_extract itself is the rootfs
        let _ = std::fs::remove_dir_all(dest);
        std::fs::rename(temp_extract, dest)
            .context("rename temp_extract rootfs to dest")?;
    } else {
        // Wrapped tarball – find the single top-level directory
        let entries: Vec<_> = std::fs::read_dir(temp_extract)
            .context("list temp dir")?
            .filter_map(|e| e.ok())
            .collect();

        if entries.is_empty() {
            anyhow::bail!("tarball extracted no files");
        }

        let top: PathBuf = entries
            .iter()
            .find(|e| is_rootfs_dir(&e.path()))
            .map(|e| e.path())
            .or_else(|| {
                entries.iter().find(|e| e.path().is_dir()).map(|e| e.path())
            })
            .ok_or_else(|| anyhow::anyhow!("no rootfs directory found in tarball"))?;

        if !top.is_dir() {
            anyhow::bail!("selected top-level entry is not a directory");
        }

        let _ = std::fs::remove_dir_all(dest);
        std::fs::rename(&top, dest).context("rename rootfs to dest")?;
        // Clean up the now-empty temp_extract
        let _ = std::fs::remove_dir_all(temp_extract);
    }

    // Ensure the root directory has standard permissions (755)
    if let Ok(metadata) = std::fs::metadata(dest) {
        let mut perms = metadata.permissions();
        perms.set_mode(0o755);
        let _ = std::fs::set_permissions(dest, perms);
    }

    std::fs::create_dir_all(dest.join("etc")).ok();

    setup_fake_sysdata(dest)?;
    patch_user_group_files(dest);
    write_fixdbus_script(dest);
    Ok(())
}

fn setup_fake_sysdata(rootfs: &Path) -> Result<()> {
    let proc_dir = rootfs.join("proc");
    let sys_empty = rootfs.join("sys/.empty");

    std::fs::create_dir_all(&proc_dir).context("create proc dir")?;
    std::fs::create_dir_all(&sys_empty).context("create sys/.empty")?;

    let _ = std::fs::set_permissions(&proc_dir, std::fs::Permissions::from_mode(0o755));
    let _ = std::fs::set_permissions(&sys_empty.parent().unwrap_or_else(|| Path::new("/")), std::fs::Permissions::from_mode(0o755));
    let _ = std::fs::set_permissions(&sys_empty, std::fs::Permissions::from_mode(0o755));

    let write_if_missing = |path: &Path, content: &str| {
        if !path.exists() {
            std::fs::write(path, content).with_context(|| format!("write {:?}", path))?;
        }
        Ok::<(), anyhow::Error>(())
    };

    write_if_missing(&rootfs.join("proc/.loadavg"), "0.12 0.07 0.02 2/165 765\n")?;
    write_if_missing(
        &rootfs.join("proc/.stat"),
        "cpu 1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n",
    )?;
    write_if_missing(&rootfs.join("proc/.uptime"), "124.08 932.80\n")?;
    write_if_missing(
        &rootfs.join("proc/.version"),
        "Linux version 6.2.1 (proot@termux) (gcc (GCC) 12.2.1 20230201, GNU ld (GNU Binutils) 2.40) #1 SMP PREEMPT_DYNAMIC Wed, 01 Mar 2023 00:00:00 +0000\n",
    )?;
    write_if_missing(
        &rootfs.join("proc/.vmstat"),
        "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
    )?;
    write_if_missing(&rootfs.join("proc/.sysctl_entry_cap_last_cap"), "0\n")?;
    write_if_missing(
        &rootfs.join("proc/.sysctl_inotify_max_user_watches"),
        "4096\n",
    )?;

    Ok(())
}

// ---------------------------------------------------------------------------
// public entry points
// ---------------------------------------------------------------------------

pub fn ensure_rootfs_with_progress(
    url: &str,
    tarball_name: &str,
    rootfs_path: &Path,
    progress: Option<ProgressFn>,
) -> Result<()> {
    let _guard = DOWNLOAD_LOCK
        .lock()
        .map_err(|e| anyhow::anyhow!("download lock poisoned: {:?}", e))?;

    let ctx = get_application_context()?;
    let cache_dir = &ctx.cache_dir;

    if has_rootfs(rootfs_path) {
        return Ok(());
    }

    let rootfs_name = rootfs_path
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("rootfs")
        .to_string();

    let report = |pct: u32, msg: &str| {
        if let Some(ref f) = progress {
            f(pct, msg);
        }
    };

    std::fs::create_dir_all(cache_dir).context("create cache dir")?;
    let tarball_path = cache_dir.join(tarball_name);
    let tarball_tmp = cache_dir.join(format!("{}.tmp", tarball_name));

    let temp_extract = rootfs_path
        .parent()
        .map(|p| p.join(format!("{}_extract_tmp", rootfs_name)))
        .unwrap_or_else(|| rootfs_path.with_extension("extract_tmp"));

    let staging_rootfs_path = rootfs_path
        .parent()
        .map(|p| p.join(format!("{}.new", rootfs_name)))
        .unwrap_or_else(|| rootfs_path.with_extension("new"));

    // 1. Download Phase
    loop {
        if tarball_path.exists() {
            log::info!("Found existing tarball in cache: {:?}", tarball_path);
            break;
        }

        let dl_label = format!("Downloading {}", rootfs_name);
        report(0, &dl_label);
        let _ = std::fs::remove_file(&tarball_tmp);

        match download_tarball_with_progress(
            &tarball_tmp,
            &tarball_path,
            0,
            70,
            &report,
            url,
            &dl_label,
        ) {
            Ok(()) => break,
            Err(e) => {
                log::warn!("{} download failed, retry: {:?}", rootfs_name, e);
                let _ = std::fs::remove_file(&tarball_tmp);
                let _ = std::fs::remove_file(&tarball_path);
            }
        }
    }

    // 2. Extraction Phase
    let ext_label = format!("Extracting {}", rootfs_name);
    report(70, &ext_label);

    if let Some(parent) = rootfs_path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::remove_dir_all(&temp_extract);
    let _ = std::fs::remove_dir_all(&staging_rootfs_path);

    match extract_tarball(&tarball_path, &staging_rootfs_path, &temp_extract) {
        Ok(()) => {
            std::thread::sleep(std::time::Duration::from_millis(500));
            validate_rootfs_structure(&staging_rootfs_path)
                .context("validate extracted rootfs structure")?;
        }
        Err(e) => {
            log::warn!("{} extract failed: {:?}", rootfs_name, e);
            let _ = std::fs::remove_dir_all(&temp_extract);
            let _ = std::fs::remove_dir_all(&staging_rootfs_path);
            let _ = std::fs::remove_file(&tarball_path);
            anyhow::bail!("Extraction failed (archive might be corrupt). Cache cleared. Please try again.");
        }
    }

    // 3. Commit Phase
    let sentinel_path = staging_rootfs_path.join(ROOTFS_READY_SENTINEL);
    std::fs::write(&sentinel_path, b"").context("write rootfs ready sentinel")?;
    if !sentinel_path.exists() {
        anyhow::bail!("sentinel file not present after write");
    }

    let had_old = rootfs_path.exists();
    let mut backup_rootfs_path: Option<PathBuf> = None;
    if had_old {
        let ts = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        let backup_name = format!("{}.bak.{}", rootfs_name, ts);
        let parent = rootfs_path
            .parent()
            .ok_or_else(|| anyhow::anyhow!("rootfs_path has no parent"))?;
        let backup_path = parent.join(backup_name);
        std::fs::rename(rootfs_path, &backup_path).context("rename old rootfs -> backup")?;
        backup_rootfs_path = Some(backup_path);
    }

    match std::fs::rename(&staging_rootfs_path, rootfs_path) {
        Ok(()) => {
            if let Some(ref backup_path) = backup_rootfs_path {
                let _ = std::fs::remove_dir_all(backup_path);
            }
        }
        Err(e) => {
            if let Some(ref backup_path) = backup_rootfs_path {
                let _ = std::fs::rename(backup_path, rootfs_path)
                    .context("rollback backup rootfs -> original")?;
            }
            return Err(e).context("atomic rootfs swap failed");
        }
    }

    let _ = std::fs::remove_file(&tarball_path);

    report(100, "Done");
    Ok(())
}

fn patch_user_group_files(rootfs: &Path) {
    let username = match std::process::Command::new("id")
        .arg("-un")
        .output()
    {
        Ok(o) => String::from_utf8_lossy(&o.stdout).trim().to_string(),
        Err(_) => return,
    };
    let uid = unsafe { libc::getuid() };
    let gid = unsafe { libc::getgid() };

    let group_names = match std::process::Command::new("id").arg("-Gn").output() {
        Ok(o) => String::from_utf8_lossy(&o.stdout).trim().to_string(),
        Err(_) => return,
    };
    let group_ids = match std::process::Command::new("id").arg("-G").output() {
        Ok(o) => String::from_utf8_lossy(&o.stdout).trim().to_string(),
        Err(_) => return,
    };

    let names: Vec<&str> = group_names.split_whitespace().collect();
    let ids: Vec<u32> = group_ids
        .split_whitespace()
        .filter_map(|s| s.parse().ok())
        .collect();

    if names.len() != ids.len() {
        log::warn!("patch_user_group_files: group name/id count mismatch");
        return;
    }

    let append = |rel: &str, content: &str| {
        let path = rootfs.join(rel);
        if path.exists() {
            let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644));
            if let Ok(mut f) = std::fs::OpenOptions::new().append(true).open(&path) {
                let _ = writeln!(f, "{}", content);
            }
        }
    };

    append(
        "etc/passwd",
        &format!(
            "aid_{}:x:{}:{}:Termux:/:/sbin/nologin",
            username, uid, gid
        ),
    );
    append(
        "etc/shadow",
        &format!("aid_{}:*:18446:0:99999:7:::", username),
    );

    for (name, grp_id) in names.iter().zip(ids.iter()) {
        append(
            "etc/group",
            &format!("aid_{}:x:{}:root,aid_{}", name, grp_id, username),
        );
        append(
            "etc/gshadow",
            &format!("aid_{}:*::root,aid_{}", name, username),
        );
    }
}

fn write_fixdbus_script(rootfs: &Path) {
    let script_path = rootfs.join("bin/fixdbus");
    let content = r##"#!/bin/sh
# Start D-Bus system bus properly for Xfce / desktop.
# Run this once before starting your desktop session.

BUS_SOCKET="/run/dbus/system_bus_socket"

# Helper: check if the daemon is actually listening on the socket
dbus_is_alive() {
    [ -S "$BUS_SOCKET" ] && dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply /org/freedesktop/DBus org.freedesktop.DBus.ListNames >/dev/null 2>&1
}

if dbus_is_alive; then
    echo "D-Bus system bus is already running."
    export DBUS_SYSTEM_BUS_ADDRESS="unix:path=$BUS_SOCKET"
    return 0
fi

# Clean up stale socket if any
if [ -S "$BUS_SOCKET" ]; then
    echo "Removing stale socket…"
    rm -f "$BUS_SOCKET"
fi

# Create directory if needed (already done by host, but just in case)
mkdir -p /run/dbus /var/run
ln -sf /run/dbus /var/run/dbus   # legacy path compatibility

# Start the daemon
echo "Starting D-Bus system daemon…"
dbus-daemon --system --fork --print-pid > /run/dbus/pid 2>/dev/null
sleep 0.5

if dbus_is_alive; then
    echo "D-Bus system bus started successfully."
    export DBUS_SYSTEM_BUS_ADDRESS="unix:path=$BUS_SOCKET"
    echo "DBUS_SYSTEM_BUS_ADDRESS=$DBUS_SYSTEM_BUS_ADDRESS"
else
    echo "ERROR: Failed to start D-Bus system daemon."
    echo "Check if dbus-daemon is installed and /proc/sys/kernel/cap_last_cap is 0."
    exit 1
fi
"##;

    if let Err(e) = std::fs::write(&script_path, content) {
        log::warn!("Failed to write fixdbus script: {:?}", e);
    } else {
        let _ = std::fs::set_permissions(&script_path, std::fs::Permissions::from_mode(0o755));
    }
}