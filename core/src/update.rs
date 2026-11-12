//! In-app updates, the way the PC launcher does them (upstream docs/release-flow.md): the newest
//! build is the GitHub release `nightly` ("Nightly <date> · <commit>"), older ones are kept as
//! `nightly-<date>-<commit>`. Every release carries SHA256SUMS and SHA256SUMS.sig (one Ed25519
//! signature per key over `horizon release nightly\n` + SHA256SUMS). Nothing is installed unless
//! one of the keys built in from android/release-keys.txt signed the list and the download matches
//! its signed SHA-256: the verification is `horizon-release-sign`, the same crate the PC launcher
//! and `horizon-server update` use.

use std::io::{Read, Write};
use std::path::Path;
use std::sync::Mutex;
use std::time::Duration;

use horizon_release_sign as sign;
use serde_json::{json, Value};

/// Where this edition publishes (a fork can point elsewhere with HORIZON_ANDROID_UPDATE_REPO).
const REPO: &str = "DreamingbullyYT/horizon-android-edition";
const KEYS: &str = include_str!("../../android/release-keys.txt");
const MANIFEST: &str = "android-manifest.json";

fn repo() -> String {
    std::env::var("HORIZON_ANDROID_UPDATE_REPO")
        .ok()
        .filter(|r| !r.is_empty())
        .unwrap_or_else(|| REPO.to_owned())
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(60))
        .user_agent("horizon-android-edition")
        .build()
}

fn get(url: &str) -> Result<ureq::Response, String> {
    agent().get(url).call().map_err(|e| match e {
        ureq::Error::Status(403 | 429, _) if url.starts_with("https://api.github.com/") => {
            "GitHub's rate limit was reached. Try again in a few minutes.".to_owned()
        }
        ureq::Error::Status(404, _) => "That release isn't published (yet).".to_owned(),
        ureq::Error::Status(code, _) => format!("{url} answered {code}"),
        ureq::Error::Transport(t) => format!("No connection: {t}"),
    })
}

fn asset_url(tag: &str, name: &str) -> String {
    format!("https://github.com/{}/releases/download/{tag}/{name}", repo())
}

fn bytes(url: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    get(url)?.into_reader().take(64 << 20).read_to_end(&mut out).map_err(|e| e.to_string())?;
    Ok(out)
}

#[derive(Default)]
struct Progress {
    running: bool,
    line: String,
    fraction: f32,
    error: Option<String>,
}

static PROGRESS: Mutex<Progress> =
    Mutex::new(Progress { running: false, line: String::new(), fraction: 0.0, error: None });

fn progress() -> std::sync::MutexGuard<'static, Progress> {
    PROGRESS.lock().unwrap_or_else(|e| e.into_inner())
}

pub fn progress_json() -> Result<String, String> {
    let p = progress();
    Ok(json!({ "running": p.running, "line": p.line, "fraction": p.fraction, "error": p.error }).to_string())
}

/// The Nightlies published, newest first: the current one (tag `nightly`) and the kept older ones.
pub fn list() -> Result<String, String> {
    let url = format!("https://api.github.com/repos/{}/releases?per_page=30", repo());
    let releases: Vec<Value> = serde_json::from_reader(get(&url)?.into_reader()).map_err(|e| format!("bad release list: {e}"))?;
    let mut items: Vec<(String, Value)> = releases
        .iter()
        .filter_map(|r| {
            let tag = r.get("tag_name")?.as_str()?;
            if !sign::is_nightly(tag) || r.get("draft").and_then(Value::as_bool).unwrap_or(false) {
                return None;
            }
            let title = r.get("name").and_then(Value::as_str).unwrap_or_default();
            let published = r.get("published_at").and_then(Value::as_str).unwrap_or_default();
            let version = sign::nightly_version(title, published);
            Some((
                published.to_owned(),
                json!({
                    "tag": tag,
                    "version": version,
                    "date": sign::nightly_date(&version),
                    "published": published,
                    "current": tag == sign::NIGHTLY,
                }),
            ))
        })
        .collect();
    items.sort_by(|a, b| b.0.cmp(&a.0));
    Ok(json!({ "releases": items.into_iter().map(|(_, v)| v).collect::<Vec<_>>() }).to_string())
}

/// The signed `(file, sha256)` list of release `tag`, or why it must be refused.
fn signed_sums(tag: &str) -> Result<Vec<(String, String)>, String> {
    let keys = sign::parse_keys(KEYS)?;
    let sums = bytes(&asset_url(tag, sign::SUMS)).map_err(|e| format!("{}: {e}", sign::SUMS))?;
    let signature = String::from_utf8_lossy(&bytes(&asset_url(tag, sign::SIGNATURE)).map_err(|e| format!("{}: {e}", sign::SIGNATURE))?)
        .into_owned();
    // Signed for the nightly channel whatever tag serves them, so a release's list can't be replayed.
    sign::verify(sign::NIGHTLY, &sums, &signature, &keys)
}

fn digest_of<'a>(sums: &'a [(String, String)], name: &str) -> Result<&'a str, String> {
    sums.iter()
        .find(|(n, _)| n == name)
        .map(|(_, h)| h.as_str())
        .ok_or_else(|| format!("{name} is not in the signed checksums, so it was not installed."))
}

fn fetch_file(tag: &str, name: &str, expected: &str, dest: &Path, share: (f32, f32)) -> Result<(), String> {
    progress().line = format!("Downloading {name}");
    let response = get(&asset_url(tag, name))?;
    let total = response.header("Content-Length").and_then(|v| v.parse::<u64>().ok()).unwrap_or(0);
    let part = dest.with_extension("part");
    let mut file = std::fs::File::create(&part).map_err(|e| format!("{}: {e}", part.display()))?;
    let mut reader = response.into_reader();
    let mut hash = sign::Sha256::default();
    let mut buffer = vec![0u8; 1 << 16];
    let mut done = 0u64;
    loop {
        let n = reader.read(&mut buffer).map_err(|e| format!("download interrupted: {e}"))?;
        if n == 0 {
            break;
        }
        hash.update(&buffer[..n]);
        file.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
        done += n as u64;
        if total > 0 {
            progress().fraction = share.0 + (share.1 - share.0) * (done as f32 / total as f32).min(1.0);
        }
    }
    file.flush().map_err(|e| e.to_string())?;
    drop(file);
    if let Err(e) = sign::check(name, expected, &hash.hex()) {
        let _ = std::fs::remove_file(&part);
        return Err(e);
    }
    std::fs::rename(&part, dest).map_err(|e| format!("{}: {e}", dest.display()))
}

/// Downloads release `tag` into `dest`, verified. `what` is `runtime` (the two libraries and the
/// engine data, per the signed android-manifest.json), `apk` (the app) or `manifest` (just the
/// signed manifest, to read its ABI level and build first). Returns the manifest.
pub fn download(tag: &str, what: &str, dest: &str) -> Result<String, String> {
    {
        let mut p = progress();
        if p.running {
            return Err("Another update is running".into());
        }
        *p = Progress { running: true, line: "Checking the signature".into(), ..Progress::default() };
    }
    let result = (|| {
        if !sign::is_nightly(tag) {
            return Err(format!("{tag} is not a Nightly build"));
        }
        let dest = Path::new(dest);
        std::fs::create_dir_all(dest).map_err(|e| format!("{}: {e}", dest.display()))?;
        let sums = signed_sums(tag)?;
        let manifest_path = dest.join(MANIFEST);
        fetch_file(tag, MANIFEST, digest_of(&sums, MANIFEST)?, &manifest_path, (0.0, 0.02))?;
        let manifest: Value = serde_json::from_slice(&std::fs::read(&manifest_path).map_err(|e| e.to_string())?)
            .map_err(|e| format!("{MANIFEST}: {e}"))?;
        let files: Vec<String> = match what {
            "apk" => vec![manifest["apk"].as_str().ok_or("this release has no app")?.to_owned()],
            "manifest" => Vec::new(),
            "runtime" => {
                let abi = &manifest["arm64-v8a"];
                ["core", "game", "data"]
                    .iter()
                    .map(|k| abi[k].as_str().map(str::to_owned).ok_or_else(|| format!("{MANIFEST} has no {k}")))
                    .collect::<Result<_, _>>()?
            }
            other => return Err(format!("unknown download kind {other}")),
        };
        for (i, name) in files.iter().enumerate() {
            // A name that isn't a plain file name never leaves `dest`.
            if name.contains('/') || name.contains('\\') || name.starts_with('.') {
                return Err(format!("unsafe file name {name}"));
            }
            let lo = 0.02 + 0.98 * i as f32 / files.len() as f32;
            let hi = 0.02 + 0.98 * (i + 1) as f32 / files.len() as f32;
            fetch_file(tag, name, digest_of(&sums, name)?, &dest.join(name), (lo, hi))?;
        }
        Ok(manifest)
    })();
    let mut p = progress();
    p.running = false;
    match result {
        Ok(manifest) => {
            p.fraction = 1.0;
            p.line = "Verified".into();
            Ok(manifest.to_string())
        }
        Err(e) => {
            p.error = Some(e.clone());
            Err(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_built_in_keys_parse() {
        assert!(!sign::parse_keys(KEYS).unwrap().is_empty());
    }

    #[test]
    fn only_a_list_signed_for_the_nightly_channel_by_a_trusted_key_passes() {
        let (key, _) = sign::SigningKey::generate().unwrap();
        let sums = sign::sums_file(&[("a.so".into(), sign::sha256_hex(b"a"))]);
        let signature = key.sign(sign::NIGHTLY, sums.as_bytes());
        let keys = [key.public()];
        assert_eq!(sign::verify(sign::NIGHTLY, sums.as_bytes(), &signature, &keys).unwrap().len(), 1);
        // replayed from a release, tampered, or signed by someone else: refused
        assert!(sign::verify("v1.0", sums.as_bytes(), &signature, &keys).is_err());
        assert!(sign::verify(sign::NIGHTLY, b"x", &signature, &keys).is_err());
        let (other, _) = sign::SigningKey::generate().unwrap();
        assert!(sign::verify(sign::NIGHTLY, sums.as_bytes(), &signature, &[other.public()]).is_err());
    }

    #[test]
    fn the_repository_secret_matches_the_built_in_key() {
        // Set ANDROID_RELEASE_SIGNING_KEY to run this against the real secret.
        let Ok(secret) = std::env::var("ANDROID_RELEASE_SIGNING_KEY") else { return };
        let key = sign::SigningKey::from_base64(&secret).unwrap();
        assert!(sign::parse_keys(KEYS).unwrap().contains(&key.public()));
    }
}
