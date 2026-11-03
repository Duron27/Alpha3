//! Wabbajack lists read into install plans.
//!
//! A `.wabbajack` file is a zip: `modlist` (JSON: the archives to download and, for every installed file, where it
//! comes from) and the list's own files, named by id (configs, meta.ini, binary patches). A Morrowind list is a Mod
//! Organizer setup: every mod is a folder `mods/<name>`, and the profile's openmw.cfg names those folders and the
//! plugins in the author's order.
//!
//! The `modlist` JSON is large (tens of MB, over 150,000 instructions), more than the app wants on its Java heap, so
//! it is read here once and written as small files that the app reads one archive at a time while it unpacks:
//!
//! * `archives.json` - the archives that feed `mods/`, with where to download them (Nexus mod and file, or a URL)
//! * `a/<index>.tsv` - per archive: `path in archive \t target [\t patch id]`, one line per installed file
//! * `inline.tsv` - the list's own files: `id \t target` (files in `mods/` and the profile's)
//!
//! Targets are relative to the install folder, with `/`. Tools and Mod Organizer itself (targets outside `mods/`)
//! are left out, and so are the archives only they use.
//!
//! Request: `{ "modlist": "<the extracted modlist file>", "out_dir": "<plan folder>" }`.
//! Answer: the list's name, author, version and so on, and counts (see [`Answer`]).

use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Write};
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
pub struct Request {
    modlist: String,
    out_dir: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ModList {
    #[serde(default)]
    name: String,
    #[serde(default)]
    author: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    website: String,
    #[serde(default)]
    readme: String,
    #[serde(default)]
    game_type: String,
    archives: Vec<Archive>,
    directives: Vec<Directive>,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Archive {
    hash: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    meta: String,
    state: ArchiveState,
}

#[derive(Deserialize)]
struct ArchiveState {
    #[serde(rename = "$type")]
    kind: String,
    #[serde(rename = "ModID", default)]
    mod_id: Option<u64>,
    #[serde(rename = "FileID", default)]
    file_id: Option<u64>,
    #[serde(rename = "Name", default)]
    name: Option<String>,
    #[serde(rename = "Author", default)]
    author: Option<String>,
    #[serde(rename = "Version", default)]
    version: Option<String>,
    #[serde(rename = "ImageURL", default)]
    image: Option<String>,
    #[serde(rename = "Url", default)]
    url: Option<String>,
}

#[derive(Deserialize)]
struct Directive {
    #[serde(rename = "$type")]
    kind: String,
    #[serde(rename = "To")]
    to: String,
    #[serde(rename = "ArchiveHashPath", default)]
    archive_hash_path: Vec<String>,
    #[serde(rename = "PatchID", default)]
    patch_id: Option<String>,
    #[serde(rename = "SourceDataID", default)]
    source_data_id: Option<String>,
    #[serde(rename = "Size", default)]
    size: u64,
}

#[derive(Serialize)]
struct PlannedArchive {
    index: usize,
    hash: String,
    name: String,
    size: u64,
    /// "nexus" or "http"; any other downloader is "unsupported" and the list cannot install
    kind: &'static str,
    mod_id: Option<u64>,
    file_id: Option<u64>,
    mod_name: Option<String>,
    author: Option<String>,
    version: Option<String>,
    image: Option<String>,
    url: Option<String>,
    files: usize,
}

#[derive(Serialize)]
pub struct Answer {
    name: String,
    author: String,
    version: String,
    description: String,
    website: String,
    readme: String,
    game: String,
    /// The Mod Organizer profile whose openmw.cfg is the list's load order
    profile: Option<String>,
    archives: usize,
    unsupported_archives: Vec<String>,
    download_bytes: u64,
    files: usize,
    install_bytes: u64,
    patched_files: usize,
    nested_files: usize,
    inline_files: usize,
}

fn slashes(path: &str) -> String {
    path.replace('\\', "/")
}

fn clean(field: &str) -> String {
    field.replace(['\t', '\n', '\r'], " ")
}

fn in_mods(to: &str) -> bool {
    to.len() > 5 && to[..5].eq_ignore_ascii_case("mods/")
}

pub fn plan(request: &Request) -> Result<Answer, String> {
    let file = File::open(&request.modlist).map_err(|e| format!("Cannot open the modlist: {e}"))?;
    let mut reader = BufReader::with_capacity(1 << 20, file);
    // The file may start with a UTF-8 byte order mark
    {
        use std::io::BufRead;
        let head = reader.fill_buf().map_err(|e| e.to_string())?;
        if head.starts_with(&[0xEF, 0xBB, 0xBF]) {
            reader.consume(3);
        }
    }
    let list: ModList = serde_json::from_reader(reader).map_err(|e| format!("Not a Wabbajack modlist: {e}"))?;

    let out = PathBuf::from(&request.out_dir);
    let _ = fs::remove_dir_all(&out);
    fs::create_dir_all(out.join("a")).map_err(|e| format!("Cannot write the plan: {e}"))?;

    // The files each archive gives, by archive hash
    let mut per_archive: BTreeMap<&str, Vec<&Directive>> = BTreeMap::new();
    let mut inline: Vec<&Directive> = Vec::new();
    let mut profile: Option<String> = None;
    let mut nested = 0;
    let mut patched = 0;
    let mut install_bytes = 0;
    for directive in &list.directives {
        let to = slashes(&directive.to);
        match directive.kind.as_str() {
            "FromArchive" | "PatchedFromArchive" if in_mods(&to) => {
                let Some(hash) = directive.archive_hash_path.first() else { continue };
                if directive.archive_hash_path.len() > 2 {
                    nested += 1;
                }
                if directive.kind == "PatchedFromArchive" {
                    patched += 1;
                }
                install_bytes += directive.size;
                per_archive.entry(hash.as_str()).or_default().push(directive);
            }
            "InlineFile" => {
                let lower = to.to_lowercase();
                if in_mods(&to) {
                    install_bytes += directive.size;
                    inline.push(directive);
                } else if lower.starts_with("profiles/") && lower.ends_with("/openmw.cfg") {
                    profile.get_or_insert_with(|| to.split('/').nth(1).unwrap_or_default().to_owned());
                    inline.push(directive);
                } else if lower.starts_with("profiles/") && lower.ends_with("/settings.cfg") {
                    inline.push(directive);
                }
            }
            _ => {}
        }
    }

    let mut planned = Vec::new();
    let mut unsupported = Vec::new();
    let mut files = 0;
    for archive in &list.archives {
        let Some(directives) = per_archive.get(archive.hash.as_str()) else { continue };
        let index = planned.len();
        let kind = if archive.state.kind.starts_with("NexusDownloader") {
            "nexus"
        } else if archive.state.kind.starts_with("HttpDownloader") {
            "http"
        } else {
            unsupported.push(format!("{} ({})", archive.name, archive.state.kind));
            "unsupported"
        };
        let url = archive.state.url.clone().or_else(|| {
            archive.meta.lines().find_map(|l| l.strip_prefix("directURL=")).map(str::to_owned)
        });

        let mut writer = BufWriter::new(
            File::create(out.join("a").join(format!("{index}.tsv"))).map_err(|e| format!("Cannot write the plan: {e}"))?,
        );
        for directive in directives {
            let inner = directive.archive_hash_path[1..].iter().map(|p| clean(&slashes(p))).collect::<Vec<_>>().join("|");
            let to = clean(&slashes(&directive.to));
            match &directive.patch_id {
                Some(patch) if directive.kind == "PatchedFromArchive" => writeln!(writer, "{inner}\t{to}\t{patch}"),
                _ => writeln!(writer, "{inner}\t{to}"),
            }
            .map_err(|e| format!("Cannot write the plan: {e}"))?;
        }
        writer.flush().map_err(|e| format!("Cannot write the plan: {e}"))?;
        files += directives.len();

        planned.push(PlannedArchive {
            index,
            hash: archive.hash.clone(),
            name: archive.name.clone(),
            size: archive.size,
            kind,
            mod_id: archive.state.mod_id,
            file_id: archive.state.file_id,
            mod_name: archive.state.name.clone(),
            author: archive.state.author.clone(),
            version: archive.state.version.clone(),
            image: archive.state.image.clone(),
            url,
            files: directives.len(),
        });
    }

    let mut writer =
        BufWriter::new(File::create(out.join("inline.tsv")).map_err(|e| format!("Cannot write the plan: {e}"))?);
    for directive in &inline {
        let Some(id) = &directive.source_data_id else { continue };
        writeln!(writer, "{}\t{}", clean(id), clean(&slashes(&directive.to))).map_err(|e| e.to_string())?;
    }
    writer.flush().map_err(|e| e.to_string())?;

    let archives_file = File::create(out.join("archives.json")).map_err(|e| format!("Cannot write the plan: {e}"))?;
    serde_json::to_writer(BufWriter::new(archives_file), &planned).map_err(|e| e.to_string())?;

    Ok(Answer {
        name: list.name,
        author: list.author,
        version: list.version,
        description: list.description,
        website: list.website,
        readme: list.readme,
        game: list.game_type,
        profile,
        archives: planned.len(),
        download_bytes: planned.iter().map(|a| a.size).sum(),
        unsupported_archives: unsupported,
        files,
        install_bytes,
        patched_files: patched,
        nested_files: nested,
        inline_files: inline.len(),
    })
}
