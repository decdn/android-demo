//! JNI bridge from the Android app to the deCDN client SDK (`decdn-client`).
//!
//! The app calls [`Java_dev_decdn_demo_Native_init`] once with its `Context`,
//! then [`Java_dev_decdn_demo_Native_pull`] on a worker thread per download,
//! and polls [`Java_dev_decdn_demo_Native_progress`] from the UI thread.
//! A pull fetches either one blob (a one-entry manifest built here) or a whole
//! bundle (its manifest fetched by hash), paying under a delegated capability.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex, OnceLock};
use std::time::Duration;

use alloy::primitives::Address;
use anyhow::{Context as _, bail};
use decdn_client::bundle::{
    BundleEnd, BundleManifest, BundleObserver, BundlePuller, FileObserver, Funding, ManifestEntry,
    parse_hash, select_nodes,
};
use decdn_client::session::{ChainConfig, DiscoveryOptions, PullSite};
use decdn_client::{CredentialSlot, endpoint};
use decdn_incentive::CapabilityGrant;
use decdn_incentive::buyer_pool_redb::RedbBuyerPoolStore;
use decdn_incentive::eth_identity::load_signer;
use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JObject, JString};
use jni::sys::{jint, jlong};
use tokio_util::sync::CancellationToken;

/// Arbitrum Sepolia testnet, from decdn's `crates/cli/deployments/421614.json`.
const RPC_URL: &str = "https://sepolia-rollup.arbitrum.io/rpc";
const CHAIN_ID: u64 = 421_614;
const PAYMENT_POOL: &str = "0x351E5544471e11638b89DF8E3A3299c28E00cEc3";
const SLASH_JUDGE: &str = "0x8e4d5945615bDF00106ff429da07bd08bCdEdB94";
const CAPACITY_BOND: &str = "0x65a9e38F41B9bE2fcB97950a0E3c33e506E6E353";

/// A pull that stops making progress for this long gives up.
const GIVE_UP_AFTER: Duration = Duration::from_secs(120);

static INIT: OnceLock<Result<(), String>> = OnceLock::new();
static CANCEL: Mutex<Option<CancellationToken>> = Mutex::new(None);
static PROGRESS: LazyLock<Progress> = LazyLock::new(Progress::default);

/// Where the running pull is, for the UI to poll.
#[derive(Default)]
struct Progress {
    /// 0 idle, 1 finding holders, 2 downloading, 3 verifying.
    phase: AtomicU8,
    /// Content bytes the run plans to pay for, when the manifest declares sizes.
    planned: AtomicU64,
    files_total: AtomicU64,
    files: Mutex<Vec<Arc<FileProgress>>>,
}

#[derive(Default)]
struct FileProgress {
    received: AtomicU64,
    expected: AtomicU64,
    done: AtomicBool,
}

impl Progress {
    fn reset(&self, files_total: usize) {
        self.phase.store(1, Ordering::Relaxed);
        self.planned.store(0, Ordering::Relaxed);
        self.files_total
            .store(files_total as u64, Ordering::Relaxed);
        self.files.lock().unwrap_or_else(|p| p.into_inner()).clear();
    }

    /// `phase,received,expected,filesDone,filesTotal`.
    fn snapshot(&self) -> String {
        let files = self.files.lock().unwrap_or_else(|p| p.into_inner());
        let received: u64 = files
            .iter()
            .map(|f| f.received.load(Ordering::Relaxed))
            .sum();
        let expected: u64 = files
            .iter()
            .map(|f| f.expected.load(Ordering::Relaxed))
            .sum();
        let done = files
            .iter()
            .filter(|f| f.done.load(Ordering::Relaxed))
            .count();
        let expected = expected.max(self.planned.load(Ordering::Relaxed));
        format!(
            "{},{received},{expected},{done},{}",
            self.phase.load(Ordering::Relaxed),
            self.files_total.load(Ordering::Relaxed),
        )
    }
}

struct Observer;

impl BundleObserver for Observer {
    fn run_started(&self, download_total: Option<u64>, _content_total: Option<u64>) {
        PROGRESS
            .planned
            .store(download_total.unwrap_or(0), Ordering::Relaxed);
    }

    fn file_started(
        &self,
        _paths: &[String],
        download_total: u64,
        _reconstruct_total: u64,
    ) -> Arc<dyn FileObserver> {
        let file = Arc::new(FileProgress::default());
        file.expected.store(download_total, Ordering::Relaxed);
        PROGRESS
            .files
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .push(Arc::clone(&file));
        file
    }
}

impl FileObserver for FileProgress {
    fn delivered(&self, received: u64, expected: u64) {
        self.received.store(received, Ordering::Relaxed);
        self.expected.store(expected, Ordering::Relaxed);
        PROGRESS.phase.store(2, Ordering::Relaxed);
    }

    fn reconstructing(&self) {
        PROGRESS.phase.store(3, Ordering::Relaxed);
    }

    fn finished(&self) {
        self.done.store(true, Ordering::Relaxed);
    }
}

/// Hands the app context to `ndk-context` (system DNS lookup in iroh) and to
/// `rustls-platform-verifier` (Android trust store for HTTPS).
/// Returns 0 on success, 1 on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_decdn_demo_Native_init<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JObject<'local>,
    context: JObject<'local>,
) -> jint {
    unowned
        .with_env(|env| -> jni::errors::Result<jint> {
            let result = INIT.get_or_init(|| {
                let vm = env.get_java_vm().map_err(|e| e.to_string())?;
                let global = env.new_global_ref(&context).map_err(|e| e.to_string())?;
                // The global ref lives for the whole process: ndk-context
                // holds the raw pointer.
                let context_raw = global.into_raw();
                // SAFETY: valid VM and a leaked global ref; called once.
                unsafe {
                    ndk_context::initialize_android_context(
                        vm.get_raw().cast(),
                        context_raw.cast(),
                    );
                }
                rustls_platform_verifier::android::init_with_env(env, context)
                    .map_err(|e| e.to_string())
            });
            Ok(if result.is_ok() { 0 } else { 1 })
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Pulls `hash` into `outDir`, blocking until the pull ends. With `bundle`
/// nonzero, `hash` names a bundle manifest and every entry lands under
/// `outDir`; otherwise the blob lands at `outDir/fileName`. `namespace` < 0
/// sends none. The key dir holds `keystore.json`, `password` and
/// `capability`. Returns `OK <summary>` or `ERR <message>`.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_decdn_demo_Native_pull<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JObject<'local>,
    hash: JString<'local>,
    namespace: jlong,
    out_dir: JString<'local>,
    file_name: JString<'local>,
    key_dir: JString<'local>,
    data_dir: JString<'local>,
    bundle: jint,
) -> JString<'local> {
    unowned
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            let request = Request {
                hash: hash.try_to_string(env)?,
                namespace: u64::try_from(namespace).ok(),
                out_dir: PathBuf::from(out_dir.try_to_string(env)?),
                file_name: file_name.try_to_string(env)?,
                key_dir: PathBuf::from(key_dir.try_to_string(env)?),
                data_dir: PathBuf::from(data_dir.try_to_string(env)?),
                bundle: bundle != 0,
            };
            let reply = match run(request) {
                Ok(summary) => format!("OK {summary}"),
                Err(e) => format!("ERR {e:#}"),
            };
            PROGRESS.phase.store(0, Ordering::Relaxed);
            env.new_string(reply)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// The running pull's progress: `phase,received,expected,filesDone,filesTotal`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_decdn_demo_Native_progress<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JObject<'local>,
) -> JString<'local> {
    unowned
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            env.new_string(PROGRESS.snapshot())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Stops the running pull where it is; a rerun resumes it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_decdn_demo_Native_cancel<'local>(
    _unowned: EnvUnowned<'local>,
    _class: JObject<'local>,
) {
    if let Some(token) = CANCEL.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
        token.cancel();
    }
}

struct Request {
    hash: String,
    namespace: Option<u64>,
    out_dir: PathBuf,
    file_name: String,
    key_dir: PathBuf,
    data_dir: PathBuf,
    bundle: bool,
}

fn run(request: Request) -> anyhow::Result<String> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("start the tokio runtime")?;
    runtime.block_on(Box::pin(pull(request)))
}

async fn pull(request: Request) -> anyhow::Result<String> {
    let hash = parse_hash(&request.hash)?;
    PROGRESS.reset(1);
    let chain = ChainConfig {
        rpc_url: RPC_URL.to_owned(),
        chain_id: CHAIN_ID,
        payment_pool: PAYMENT_POOL.parse::<Address>()?,
        slash_judge: SLASH_JUDGE.parse::<Address>()?,
        capacity_bond: Some(CAPACITY_BOND.parse::<Address>()?),
    };
    let site = PullSite {
        data_dir: request.data_dir.clone(),
        region: None,
        region_allowlist: Vec::new(),
    };
    let discovery = DiscoveryOptions::default();

    // Resolve the holders first, so a failed discovery costs no key unlock.
    let nodes = select_nodes(&chain, &site, &discovery).await?;
    let slot = credential(&request.key_dir)?;
    let store = Arc::new(RedbBuyerPoolStore::open(&site.data_dir)?);
    let relays = endpoint::resolve_relays(None, None)?;
    let endpoint = endpoint::client_endpoint(&relays, &endpoint::client_discovery(None)?).await?;
    let mut puller = BundlePuller::builder(
        chain,
        site,
        endpoint.clone(),
        store,
        Funding::Delegated { slot },
        discovery,
        nodes,
    )
    .relays(relays)
    .namespace(request.namespace)
    .stop(false, Some(GIVE_UP_AFTER))
    .observer(Arc::new(Observer))
    .build()
    .await?;

    let manifest = if request.bundle {
        puller.fetch_manifest(hash, &request.out_dir, true).await?
    } else {
        let hex: String = hash.iter().map(|b| format!("{b:02x}")).collect();
        BundleManifest::new(vec![ManifestEntry::new(
            request.file_name.clone(),
            format!("b3:{hex}"),
            None,
        )])
    };
    PROGRESS.reset(manifest.entries.len());

    let cancel = CancellationToken::new();
    *CANCEL.lock().unwrap_or_else(|p| p.into_inner()) = Some(cancel.clone());
    let outcome = puller
        .pull(&manifest, &request.out_dir, false, &cancel)
        .await;
    *CANCEL.lock().unwrap_or_else(|p| p.into_inner()) = None;
    // Every lane's payment lands in the store before the endpoint closes.
    puller.finish().await;
    endpoint.close().await;

    let report = &outcome.report;
    let mut summary = format!(
        "{} fetched, {} skipped, {} failed; paid for {} bytes",
        report.fetched, report.skipped, report.failed, report.downloaded
    );
    for failure in &report.failures {
        summary.push_str(&format!("\n{}: {}", failure.path, failure.error));
    }
    match outcome.end {
        BundleEnd::Complete if report.failed == 0 => Ok(summary),
        BundleEnd::Complete => bail!("{summary}"),
        BundleEnd::FundingNeeded { error, .. } => Err(error.context("the capability ran out")),
        BundleEnd::Stopped(error) => Err(error),
        _ => bail!("stopped early; download again to resume\n{summary}"),
    }
}

/// The delegate key and its capability, from the key dir.
fn credential(dir: &Path) -> anyhow::Result<CredentialSlot> {
    let password = std::fs::read_to_string(dir.join("password")).context("read password")?;
    let password = password.strip_suffix('\n').unwrap_or(&password);
    let signer = load_signer(&dir.join("keystore.json"), password)?;
    let token = std::fs::read_to_string(dir.join("capability")).context("read capability")?;
    let grant = CapabilityGrant::from_token(token.trim())?;
    Ok(CredentialSlot::new(
        Arc::new(signer),
        grant.to_signed_capability()?,
    )?)
}
