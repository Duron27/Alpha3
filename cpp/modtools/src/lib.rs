//! Alpha3's native mod tools: LOOT's load order sorting for the mod list organizer (here), and Wabbajack lists read
//! into install plans ([`wabbajack`]). Each is one call, a JSON request in and a JSON answer out.
//!
//! ## LOOT
//!
//! libloot works on an installed game: it reads the load order from openmw.cfg and finds plugins in its data
//! folders. The launcher has neither in the shape libloot expects (a list's order lives in the app until it is
//! installed), so each call builds a throwaway OpenMW install in `work_dir` (an empty resources/vfs and an openmw.cfg
//! with the request's data folders and plugins), loads the plugins from their real paths and asks for the sorted
//! order. Nothing outside `work_dir` is written: the order is only returned, and the app applies it when asked.
//!
//! Request (JSON):
//! ```json
//! { "work_dir": "...", "masterlist": "...", "prelude": "..." | null, "userlist": "..." | null,
//!   "data_paths": ["/abs/data/folder", ...],          // in openmw.cfg order
//!   "plugins": [{ "name": "X.esp", "path": "/abs/X.esp" }, ...],  // in the current order
//!   "language": "en" }
//! ```
//! Answer: `{ "order": [names...], "messages": [{ "plugin", "level", "text" }...] }` or `{ "error": "..." }`.

use std::ffi::{CStr, CString, c_char};
use std::fs;
use std::path::{Path, PathBuf};

use libloot::metadata::{MessageType, select_message_content};
use libloot::{EvalMode, Game, GameType, MergeMode};
use serde::{Deserialize, Serialize};

mod wabbajack;

#[derive(Deserialize)]
struct Plugin {
    name: String,
    path: String,
}

#[derive(Deserialize)]
struct Request {
    work_dir: String,
    masterlist: String,
    prelude: Option<String>,
    userlist: Option<String>,
    #[serde(default)]
    data_paths: Vec<String>,
    plugins: Vec<Plugin>,
    #[serde(default = "english")]
    language: String,
}

fn english() -> String {
    "en".to_owned()
}

#[derive(Serialize)]
struct Message {
    plugin: String,
    level: &'static str,
    text: String,
}

#[derive(Serialize)]
struct Answer {
    order: Vec<String>,
    messages: Vec<Message>,
}

fn quote(path: &str) -> String {
    // openmw.cfg's quoting: "..." with & escaping & and "
    format!("\"{}\"", path.replace('&', "&&").replace('"', "&\""))
}

fn sort(request: &Request) -> Result<Answer, String> {
    let work = PathBuf::from(&request.work_dir);
    let game_dir = work.join("game");
    let local_dir = work.join("local");
    let _ = fs::remove_dir_all(&work);
    fs::create_dir_all(game_dir.join("resources/vfs")).map_err(|e| format!("work folder: {e}"))?;
    fs::create_dir_all(&local_dir).map_err(|e| format!("work folder: {e}"))?;

    let mut cfg = String::new();
    for data in &request.data_paths {
        cfg.push_str(&format!("data={}\n", quote(data)));
    }
    for plugin in &request.plugins {
        cfg.push_str(&format!("content={}\n", plugin.name));
    }
    // Both: libloot reads the global config next to the game and the user's in the local folder
    fs::write(game_dir.join("openmw.cfg"), "").map_err(|e| format!("openmw.cfg: {e}"))?;
    fs::write(local_dir.join("openmw.cfg"), &cfg).map_err(|e| format!("openmw.cfg: {e}"))?;

    let mut game = Game::with_local_path(GameType::OpenMW, &game_dir, &local_dir)
        .map_err(|e| format!("LOOT could not set up the game: {e}"))?;

    {
        let database = game.database();
        let mut database = database.write().map_err(|_| "LOOT's database is unusable".to_owned())?;
        let masterlist = Path::new(&request.masterlist);
        match request.prelude.as_deref().map(Path::new).filter(|p| p.is_file()) {
            Some(prelude) => database.load_masterlist_with_prelude(masterlist, prelude),
            None => database.load_masterlist(masterlist),
        }
        .map_err(|e| format!("LOOT could not read the masterlist: {e}"))?;
        if let Some(userlist) = request.userlist.as_deref().map(Path::new).filter(|p| p.is_file()) {
            database.load_userlist(userlist).map_err(|e| format!("LOOT could not read the userlist: {e}"))?;
        }
    }

    let paths: Vec<PathBuf> = request.plugins.iter().map(|p| PathBuf::from(&p.path)).collect();
    let refs: Vec<&Path> = paths.iter().map(PathBuf::as_path).collect();
    game.load_plugins(&refs).map_err(|e| format!("LOOT could not read the plugins: {e}"))?;

    let names: Vec<&str> = request.plugins.iter().map(|p| p.name.as_str()).collect();
    let order = game.sort_plugins(&names).map_err(|e| format!("LOOT could not sort: {e}"))?;

    // What the masterlist says about the plugins (missing requirements, incompatibilities, notes)
    let mut messages = Vec::new();
    let loaded: Vec<String> = names.iter().map(|n| n.to_lowercase()).collect();
    if let Ok(database) = game.database().read() {
        for name in &names {
            let Ok(Some(meta)) = database.plugin_metadata(name, MergeMode::WithUserMetadata, EvalMode::Evaluate) else {
                continue;
            };
            for message in meta.messages() {
                let Some(content) = select_message_content(message.content(), &request.language) else { continue };
                let level = match message.message_type() {
                    MessageType::Say => "note",
                    MessageType::Warn => "warning",
                    MessageType::Error => "error",
                };
                messages.push(Message { plugin: (*name).to_owned(), level, text: content.text().to_owned() });
            }
            for file in meta.requirements() {
                let needed = file.name().as_str().to_owned();
                if !loaded.contains(&needed.to_lowercase()) && !Path::new(&needed).extension().is_none() {
                    messages.push(Message { plugin: (*name).to_owned(), level: "warning", text: format!("Needs {needed}, which is not in the load order") });
                }
            }
            for file in meta.incompatibilities() {
                let other = file.name().as_str().to_owned();
                if loaded.contains(&other.to_lowercase()) {
                    messages.push(Message { plugin: (*name).to_owned(), level: "error", text: format!("Does not work together with {other}") });
                }
            }
        }
    }

    let _ = fs::remove_dir_all(&work);
    Ok(Answer { order, messages })
}

/// Runs `call` on the request, turning every failure (a bad request, an error, a panic) into `{"error": ...}`.
fn answer<Req, Ans>(request_json: &str, call: fn(&Req) -> Result<Ans, String>) -> String
where
    Req: for<'de> Deserialize<'de>,
    Ans: Serialize,
{
    let result = std::panic::catch_unwind(|| {
        let request: Req = serde_json::from_str(request_json).map_err(|e| format!("Bad request: {e}"))?;
        call(&request)
    });
    match result {
        Ok(Ok(answer)) => serde_json::to_string(&answer).unwrap_or_else(|e| error(&e.to_string())),
        Ok(Err(message)) => error(&message),
        Err(_) => error("The mod tools stopped with an internal error"),
    }
}

fn c_string(request_json: *const c_char) -> String {
    if request_json.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(request_json) }.to_string_lossy().into_owned()
    }
}

fn error(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

/// # Safety
/// `request_json` must be a valid, NUL-terminated UTF-8 string. Free the answer with [`alpha3_modtools_free`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn alpha3_loot_sort(request_json: *const c_char) -> *mut c_char {
    CString::new(answer(&c_string(request_json), sort)).unwrap_or_default().into_raw()
}

/// # Safety
/// `request_json` must be a valid, NUL-terminated UTF-8 string. Free the answer with [`alpha3_modtools_free`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn alpha3_wabbajack_plan(request_json: *const c_char) -> *mut c_char {
    CString::new(answer(&c_string(request_json), wabbajack::plan)).unwrap_or_default().into_raw()
}

/// # Safety
/// `answer` must come from one of the calls above, and be freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn alpha3_modtools_free(answer: *mut c_char) {
    if !answer.is_null() {
        drop(unsafe { CString::from_raw(answer) });
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn alpha3_modtools_version() -> *const c_char {
    concat!("libloot ", "0.29.7", "\0").as_ptr().cast()
}

#[cfg(target_os = "android")]
mod android {
    use jni::JNIEnv;
    use jni::objects::{JClass, JString};
    use jni::sys::jstring;

    fn call(env: &mut JNIEnv, request: &JString, run: fn(&str) -> String) -> jstring {
        let request: String = env.get_string(request).map(Into::into).unwrap_or_default();
        env.new_string(run(&request)).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    /// `org.alpha3.launcher.mods.downloader.ModToolsNative.lootSort(String): String`
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_alpha3_launcher_mods_downloader_ModToolsNative_lootSort<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass<'a>,
        request: JString<'a>,
    ) -> jstring {
        call(&mut env, &request, |r| super::answer(r, super::sort))
    }

    /// `org.alpha3.launcher.mods.downloader.ModToolsNative.wabbajackPlan(String): String`
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_org_alpha3_launcher_mods_downloader_ModToolsNative_wabbajackPlan<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass<'a>,
        request: JString<'a>,
    ) -> jstring {
        call(&mut env, &request, |r| super::answer(r, super::wabbajack::plan))
    }
}
