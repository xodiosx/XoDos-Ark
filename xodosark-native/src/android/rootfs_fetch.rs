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
use std::fs::File;
use std::io::{BufReader, Read, Write};
use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use flate2::read::GzDecoder;

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

    // Use the clean_url here
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
    // 1. Check for a usable shell (standard paths)
    let shell_candidates = [
        "bin/sh", "usr/bin/sh",
        "bin/bash", "usr/bin/bash",
        "bin/dash", "usr/bin/dash",
        "bin/ash", "usr/bin/ash",
        "bin/busybox", "usr/bin/busybox",
    ];
    let has_shell = shell_candidates.iter().any(|p| rootfs_path.join(p).exists());

    // 2. If no shell, check for at least one executable in /bin or /usr/bin
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

    // 3. Fallback: if still no executable, accept the rootfs if it has the core directories /usr, /var, /etc
    let has_directory_structure = if !has_shell && !has_exec {
        let essential_dirs = ["usr", "var", "etc"];
        essential_dirs.iter().all(|d| rootfs_path.join(d).is_dir())
    } else { false };

    anyhow::ensure!(
        has_shell || has_exec || has_directory_structure,
        "no usable shell or executable found, and missing core directories (/usr, /var, /etc)"
    );

    // Ensure /proc directory exists (already created by setup_fake_sysdata before validation)
    anyhow::ensure!(rootfs_path.join("proc").is_dir(), "missing proc/");

    Ok(())
}


/// Safely unpacks an archive while bypassing Android's strict hard-link restrictions
fn unpack_archive_safe<R: Read>(mut archive: tar::Archive<R>, temp_extract: &Path) -> Result<()> {
    for entry_result in archive.entries().context("failed to read archive entries")? {
        let mut entry = match entry_result {
            Ok(e) => e,
            Err(err) => {
                log::warn!("Skipping a corrupted archive entry: {:?}", err);
                continue;
            }
        };

        let path = entry.path()?.to_path_buf();
        let dest_path = temp_extract.join(path);

        // Check if the entry type is a hard link
        if entry.header().entry_type().is_hard_link() {
            // Try unpacking it normally first
            if let Err(err) = entry.unpack(&dest_path) {
                log::warn!("Hard link creation blocked ({:?}). Falling back to dummy placeholder.", err);
                
                // Fallback: Create an empty file placeholder so extraction doesn't crash
                if let Some(parent) = dest_path.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                if let Err(write_err) = std::fs::write(&dest_path, b"") {
                    log::error!("Failed to write placeholder for link: {:?}", write_err);
                }
            }
        } else {
            // Normal files, directories, and symlinks go here
            if let Err(err) = entry.unpack(&dest_path) {
                log::warn!("Skipping entry due to unpack error on {:?}: {:?}", dest_path, err);
            }
        }
    }
    Ok(())
}

fn extract_tarball(tarball_path: &Path, dest: &Path, temp_extract: &Path) -> Result<()> {
    let file = File::open(tarball_path).context("open tarball")?;
    
    std::fs::create_dir_all(temp_extract).context("create temp extract dir")?;

    // Detect the file extension to choose the right decompressor
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
        // Default to XZ
        let xz_decoder = xz2::read::XzDecoder::new(BufReader::new(file));
        let archive = tar::Archive::new(xz_decoder);
        unpack_archive_safe(archive, temp_extract).context("extract tar.xz tarball")?;
    }

    let subdirs: Vec<_> = std::fs::read_dir(temp_extract)
        .context("list temp dir")?
        .filter_map(|e| e.ok())
        .collect();

    if subdirs.len() != 1 {
        let file_list = subdirs.iter().map(|e| e.file_name().to_string_lossy().into_owned()).collect::<Vec<_>>();
        log::error!("CRITICAL: Archive structure invalid. Found {} items: {:?}", subdirs.len(), file_list);
        
        anyhow::bail!(
            "tarball has {} top-level entries, expected 1",
            subdirs.len()
        );
    }
    
    let top = subdirs[0].path();
    if !top.is_dir() {
        anyhow::bail!("tarball top-level is not a directory");
    }
    let _ = std::fs::remove_dir_all(dest);
    std::fs::rename(&top, dest).context("rename rootfs to dest")?;
    std::fs::remove_dir_all(temp_extract).ok();
    setup_fake_sysdata(dest)?;
    Ok(())
}

fn setup_fake_sysdata(rootfs: &Path) -> Result<()> {
    let proc_dir = rootfs.join("proc");
    let sys_empty = rootfs.join("sys/.empty");
    std::fs::create_dir_all(&proc_dir).context("create proc dir")?;
    std::fs::create_dir_all(&sys_empty).context("create sys/.empty")?;

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
    write_if_missing(&rootfs.join("proc/.sysctl_entry_cap_last_cap"), "40\n")?;
    write_if_missing(
        &rootfs.join("proc/.sysctl_inotify_max_user_watches"),
        "4096\n",
    )?;

    Ok(())
}

// ---------------------------------------------------------------------------
// public entry points
// ---------------------------------------------------------------------------

/// Generic rootfs installation. Parameters should be passed in via Kotlin/JNI.
/// 
/// `url`: The full HTTP/HTTPS URL of the tar.xz archive.
/// `tarball_name`: The filename to save inside the app's cache directory (e.g. "my_distro.tar.xz").
/// `rootfs_path`: The full path where the rootfs should be extracted (e.g. "/data/data/com.app/files/rootfs/my_distro").
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
            // Because checksums are disabled, we assume an existing file is complete.
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
                // Wait for the file system to settle – important for very small rootfs archives
        std::thread::sleep(std::time::Duration::from_millis(500));
        
            validate_rootfs_structure(&staging_rootfs_path)
                .context("validate extracted rootfs structure")?;
        }
        Err(e) => {
            // Because checksums are disabled, an extraction failure often means the cache is corrupt.
            // We purge the bad cache and abort, prompting the user/system to retry and fetch a fresh copy.
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
    let mut backup_rootfs_path: Option<std::path::PathBuf> = None;
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

    // Cleanup local cache tarball to save space, since we don't have checksums to verify it later anyway
    let _ = std::fs::remove_file(&tarball_path);

    report(100, "Done");
    Ok(())
}
