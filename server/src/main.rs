use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use serde_json::json;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::thread;
use tiny_http::{Header, Request, Response, Server, StatusCode};

struct Config {
    host: String,
    port: u16,
    root: PathBuf,
}

fn parse_config() -> Config {
    let mut host = std::env::var("MEDIA_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let mut port: u16 = std::env::var("MEDIA_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(10930);
    let mut root = std::env::var("MEDIA_ROOT").unwrap_or_else(|_| "/storage/emulated/0".to_string());

    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            "-H" | "--host" => {
                if i + 1 < args.len() {
                    host = args[i + 1].clone();
                    i += 1;
                }
            }
            "-p" | "--port" => {
                if i + 1 < args.len() {
                    if let Ok(p) = args[i + 1].parse() {
                        port = p;
                    }
                    i += 1;
                }
            }
            "-r" | "--root" => {
                if i + 1 < args.len() {
                    root = args[i + 1].clone();
                    i += 1;
                }
            }
            _ => {}
        }
        i += 1;
    }

    Config {
        host,
        port,
        root: PathBuf::from(root),
    }
}

fn print_help() {
    println!("media-server — простой файловый HTTP-сервер");
    println!();
    println!("Использование: media-server [опции]");
    println!("  -H, --host <адрес>   адрес прослушивания (по умолчанию 0.0.0.0)");
    println!("  -p, --port <порт>    порт (по умолчанию 10930)");
    println!("  -r, --root <путь>    корневая папка (по умолчанию /storage/emulated/0)");
    println!("  -h, --help           показать эту справку");
    println!();
    println!("Переменные окружения: MEDIA_HOST, MEDIA_PORT, MEDIA_ROOT");
}

fn main() {
    let config = Arc::new(parse_config());
    let addr = format!("{}:{}", config.host, config.port);

    let server = match Server::http(addr.as_str()) {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("Не удалось запустить сервер на {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    println!("media-server слушает на http://{}", addr);
    println!("Корневая папка: {}", config.root.display());

    let workers = thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .max(4);

    let mut handles = Vec::new();
    for _ in 0..workers {
        let server = Arc::clone(&server);
        let config = Arc::clone(&config);
        handles.push(thread::spawn(move || loop {
            match server.recv() {
                Ok(request) => handle(request, &config),
                Err(_) => break,
            }
        }));
    }
    for h in handles {
        let _ = h.join();
    }
}

fn handle(request: Request, config: &Config) {
    let url = request.url().to_string();
    let method_str = format!("{:?}", request.method());
    let (path_part, query_part) = match url.find('?') {
        Some(idx) => (&url[..idx], &url[idx + 1..]),
        None => (url.as_str(), ""),
    };

    let status = if path_part == "/" || path_part == "/health" {
        respond_text(request, 200, "media-server ok")
    } else if path_part == "/list" {
        let rel = query_get(query_part, "path").unwrap_or_default();
        respond_list(request, config, &rel)
    } else if path_part == "/search" {
        let q = query_get(query_part, "q").unwrap_or_default();
        let rel = query_get(query_part, "path").unwrap_or_default();
        respond_search(request, config, &q, &rel)
    } else if path_part == "/download" {
        let rel = query_get(query_part, "path").unwrap_or_default();
        respond_download(request, config, &rel)
    } else {
        respond_text(request, 404, "not found")
    };

    println!("{} {} -> {}", method_str, url, status);
}

fn query_get(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        let mut it = pair.splitn(2, '=');
        let k = it.next().unwrap_or("");
        if k == key {
            let v = it.next().unwrap_or("");
            let replaced = v.replace('+', " ");
            let decoded = percent_decode_str(&replaced).decode_utf8_lossy().to_string();
            return Some(decoded);
        }
    }
    None
}

fn resolve(root: &Path, rel: &str) -> Option<PathBuf> {
    let mut path = root.to_path_buf();
    for comp in Path::new(rel).components() {
        match comp {
            Component::Normal(part) => path.push(part),
            Component::CurDir => {}
            _ => return None,
        }
    }
    Some(path)
}

fn respond_text(request: Request, code: u16, body: &str) -> u16 {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"text/plain; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(body)
        .with_status_code(StatusCode(code))
        .with_header(header);
    let _ = request.respond(response);
    code
}

fn respond_json(request: Request, code: u16, value: serde_json::Value) -> u16 {
    let body = value.to_string();
    let header =
        Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(body)
        .with_status_code(StatusCode(code))
        .with_header(header);
    let _ = request.respond(response);
    code
}

fn respond_list(request: Request, config: &Config, rel: &str) -> u16 {
    let dir = match resolve(&config.root, rel) {
        Some(p) => p,
        None => return respond_text(request, 400, "bad path"),
    };
    let read = match fs::read_dir(&dir) {
        Ok(r) => r,
        Err(_) => return respond_text(request, 404, "not found"),
    };
    let mut list: Vec<(String, bool, u64)> = Vec::new();
    for item in read {
        if let Ok(entry) = item {
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.file_type().is_symlink() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            let is_dir = meta.is_dir();
            let size = if is_dir { 0 } else { meta.len() };
            list.push((name, is_dir, size));
        }
    }
    list.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    let entries: Vec<serde_json::Value> = list
        .iter()
        .map(|(name, is_dir, size)| json!({ "name": name, "is_dir": is_dir, "size": size }))
        .collect();
    let payload = json!({
        "path": rel,
        "abs_path": dir.display().to_string(),
        "entries": entries,
    });
    respond_json(request, 200, payload)
}

fn respond_search(request: Request, config: &Config, query: &str, rel: &str) -> u16 {
    let base = match resolve(&config.root, rel) {
        Some(p) => p,
        None => return respond_text(request, 400, "bad path"),
    };
    let needle = query.to_lowercase();
    let limit = 500usize;
    let mut results: Vec<(String, bool, u64, String)> = Vec::new();
    let mut stack: Vec<PathBuf> = vec![base];

    while let Some(current) = stack.pop() {
        if results.len() >= limit {
            break;
        }
        let read = match fs::read_dir(&current) {
            Ok(r) => r,
            Err(_) => continue,
        };
        for item in read {
            let entry = match item {
                Ok(e) => e,
                Err(_) => continue,
            };
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.file_type().is_symlink() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            let full = entry.path();
            let is_dir = meta.is_dir();
            if is_dir {
                stack.push(full.clone());
            }
            if !needle.is_empty() && name.to_lowercase().contains(&needle) {
                let relpath = full
                    .strip_prefix(&config.root)
                    .unwrap_or(&full)
                    .to_string_lossy()
                    .to_string();
                let size = if is_dir { 0 } else { meta.len() };
                results.push((name, is_dir, size, relpath));
                if results.len() >= limit {
                    break;
                }
            }
        }
    }

    results.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    let entries: Vec<serde_json::Value> = results
        .iter()
        .map(|(name, is_dir, size, path)| {
            json!({ "name": name, "is_dir": is_dir, "size": size, "path": path })
        })
        .collect();
    let payload = json!({
        "query": query,
        "count": entries.len(),
        "entries": entries,
    });
    respond_json(request, 200, payload)
}

fn parse_range(range: &str, total: u64) -> Option<(u64, u64)> {
    let range = range.trim();
    if !range.starts_with("bytes=") {
        return None;
    }
    let spec = &range[6..];
    let mut parts = spec.splitn(2, '-');
    let start_s = parts.next().unwrap_or("");
    let end_s = parts.next().unwrap_or("");
    if start_s.is_empty() {
        let suffix: u64 = end_s.parse().ok()?;
        if suffix == 0 || total == 0 {
            return None;
        }
        let suffix = suffix.min(total);
        return Some((total - suffix, total - 1));
    }
    let start: u64 = start_s.parse().ok()?;
    let end: u64 = if end_s.is_empty() {
        total - 1
    } else {
        end_s.parse().ok()?
    };
    if start > end || start >= total {
        return None;
    }
    Some((start, end.min(total - 1)))
}

fn respond_download(request: Request, config: &Config, rel: &str) -> u16 {
    let path = match resolve(&config.root, rel) {
        Some(p) => p,
        None => return respond_text(request, 400, "bad path"),
    };
    let meta = match fs::metadata(&path) {
        Ok(m) => m,
        Err(_) => return respond_text(request, 404, "not found"),
    };
    if !meta.is_file() {
        return respond_text(request, 400, "not a file");
    }
    let total = meta.len();

    let mut range_header: Option<String> = None;
    for h in request.headers() {
        if h.field.equiv("Range") {
            range_header = Some(h.value.as_str().to_string());
        }
    }

    let filename = path
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());
    let encoded_name = utf8_percent_encode(&filename, NON_ALPHANUMERIC).to_string();
    let disposition = format!("attachment; filename*=UTF-8''{}", encoded_name);

    let mut file = match File::open(&path) {
        Ok(f) => f,
        Err(_) => return respond_text(request, 404, "not found"),
    };

    let ct = Header::from_bytes(&b"Content-Type"[..], &b"application/octet-stream"[..]).unwrap();
    let cd = Header::from_bytes(&b"Content-Disposition"[..], disposition.as_bytes()).unwrap();
    let ar = Header::from_bytes(&b"Accept-Ranges"[..], &b"bytes"[..]).unwrap();

    if let Some(rh) = range_header {
        if let Some((start, end)) = parse_range(&rh, total) {
            let length = end - start + 1;
            if file.seek(SeekFrom::Start(start)).is_err() {
                return respond_text(request, 500, "seek error");
            }
            let reader = file.take(length);
            let cr_value = format!("bytes {}-{}/{}", start, end, total);
            let cr = Header::from_bytes(&b"Content-Range"[..], cr_value.as_bytes()).unwrap();
            let response = Response::new(
                StatusCode(206),
                vec![ct, cd, ar, cr],
                reader,
                Some(length as usize),
                None,
            );
            let _ = request.respond(response);
            return 206;
        }
    }

    let response = Response::new(
        StatusCode(200),
        vec![ct, cd, ar],
        file,
        Some(total as usize),
        None,
    );
    let _ = request.respond(response);
    200
}
