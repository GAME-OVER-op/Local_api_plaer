// media-server — лёгкий многопоточный файловый HTTP-сервер.
// Раздаёт файлы из указанной папки по LAN, поддерживает Range (перемотку) и поиск.

use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use serde_json::json;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::thread;
use tiny_http::{Header, Request, Response, Server, StatusCode};

fn main() {
    let mut host = std::env::var("MEDIA_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let mut port = std::env::var("MEDIA_PORT")
        .ok()
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(10930);
    let mut root_str =
        std::env::var("MEDIA_ROOT").unwrap_or_else(|_| "/storage/emulated/0".to_string());

    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "-H" | "--host" => {
                i += 1;
                if i < args.len() {
                    host = args[i].clone();
                }
            }
            "-p" | "--port" => {
                i += 1;
                if i < args.len() {
                    if let Ok(v) = args[i].parse() {
                        port = v;
                    }
                }
            }
            "-r" | "--root" => {
                i += 1;
                if i < args.len() {
                    root_str = args[i].clone();
                }
            }
            "-h" | "--help" => {
                print_help();
                return;
            }
            _ => {}
        }
        i += 1;
    }

    let root = PathBuf::from(&root_str);
    let addr = format!("{}:{}", host, port);
    let server = match Server::http(&addr) {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("Не удалось запустить сервер на {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    let workers = thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .max(4);

    println!("media-server слушает на http://{}", addr);
    println!("Корень: {}", root.display());
    println!("Потоков: {}", workers);

    let mut handles = Vec::new();
    for _ in 0..workers {
        let server = Arc::clone(&server);
        let root = root.clone();
        handles.push(thread::spawn(move || loop {
            match server.recv() {
                Ok(rq) => handle(rq, &root),
                Err(_) => break,
            }
        }));
    }
    for h in handles {
        let _ = h.join();
    }
}

fn print_help() {
    println!("media-server — файловый HTTP-сервер");
    println!();
    println!("Использование: media-server [опции]");
    println!("  -H, --host <IP>     адрес прослушивания (по умолчанию 0.0.0.0)");
    println!("  -p, --port <PORT>   порт (по умолчанию 10930)");
    println!("  -r, --root <DIR>    корневая папка (по умолчанию /storage/emulated/0)");
    println!("  -h, --help          показать эту справку");
    println!();
    println!("Переменные окружения: MEDIA_HOST, MEDIA_PORT, MEDIA_ROOT");
}

fn handle(request: Request, root: &Path) {
    let method_str = format!("{:?}", request.method());
    let url = request.url().to_string();
    let (path_part, query) = match url.split_once('?') {
        Some((p, q)) => (p.to_string(), q.to_string()),
        None => (url.clone(), String::new()),
    };

    let status: u16 = match path_part.as_str() {
        "/" | "/health" => respond_text(request, 200, "text/plain; charset=utf-8", "media-server ok"),
        "/list" => {
            let rel = query_get(&query, "path").unwrap_or_default();
            respond_list(request, root, &rel)
        }
        "/search" => {
            let q = query_get(&query, "q").unwrap_or_default();
            let base = query_get(&query, "path").unwrap_or_default();
            respond_search(request, root, &q, &base)
        }
        "/download" => {
            let rel = query_get(&query, "path").unwrap_or_default();
            respond_download(request, root, &rel)
        }
        _ => respond_text(request, 404, "text/plain; charset=utf-8", "not found"),
    };

    println!("{} {} -> {}", method_str, url, status);
}

fn respond_text(request: Request, code: u16, ctype: &str, body: &str) -> u16 {
    let header = Header::from_bytes(&b"Content-Type"[..], ctype.as_bytes()).unwrap();
    let response = Response::from_string(body)
        .with_status_code(code)
        .with_header(header);
    let _ = request.respond(response);
    code
}

fn respond_json(request: Request, code: u16, value: serde_json::Value) -> u16 {
    let header =
        Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(value.to_string())
        .with_status_code(code)
        .with_header(header);
    let _ = request.respond(response);
    code
}

fn respond_list(request: Request, root: &Path, rel: &str) -> u16 {
    let path = match resolve(root, rel) {
        Some(p) => p,
        None => return respond_text(request, 400, "text/plain; charset=utf-8", "bad path"),
    };
    let rd = match fs::read_dir(&path) {
        Ok(r) => r,
        Err(_) => return respond_text(request, 404, "text/plain; charset=utf-8", "not found"),
    };
    let mut entries: Vec<(String, bool, u64)> = Vec::new();
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        let meta = match e.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let is_dir = meta.is_dir();
        let size = if is_dir { 0 } else { meta.len() };
        entries.push((name, is_dir, size));
    }
    entries.sort_by(|a, b| {
        b.1.cmp(&a.1)
            .then(a.0.to_lowercase().cmp(&b.0.to_lowercase()))
    });
    let arr: Vec<serde_json::Value> = entries
        .iter()
        .map(|(n, d, s)| json!({"name": n, "is_dir": d, "size": s}))
        .collect();
    respond_json(
        request,
        200,
        json!({"path": rel, "abs_path": path.display().to_string(), "entries": arr}),
    )
}

fn respond_search(request: Request, root: &Path, q: &str, base: &str) -> u16 {
    if q.is_empty() {
        return respond_json(request, 200, json!({"query": q, "count": 0, "entries": []}));
    }
    let start = match resolve(root, base) {
        Some(p) => p,
        None => return respond_text(request, 400, "text/plain; charset=utf-8", "bad path"),
    };
    let needle = q.to_lowercase();
    let mut out: Vec<(String, String, bool, u64)> = Vec::new();
    let mut stack: Vec<PathBuf> = vec![start];
    'walk: while let Some(dir) = stack.pop() {
        let rd = match fs::read_dir(&dir) {
            Ok(r) => r,
            Err(_) => continue,
        };
        for e in rd.flatten() {
            let meta = match e.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            // Пропускаем симлинки, чтобы не зациклиться.
            if meta.file_type().is_symlink() {
                continue;
            }
            let name = e.file_name().to_string_lossy().to_string();
            let full = e.path();
            let is_dir = meta.is_dir();
            if is_dir {
                stack.push(full.clone());
            }
            if name.to_lowercase().contains(&needle) {
                let rel = full
                    .strip_prefix(root)
                    .unwrap_or(&full)
                    .to_string_lossy()
                    .to_string();
                let size = if is_dir { 0 } else { meta.len() };
                out.push((name, rel, is_dir, size));
                if out.len() >= 500 {
                    break 'walk;
                }
            }
        }
    }
    out.sort_by(|a, b| {
        b.2.cmp(&a.2)
            .then(a.0.to_lowercase().cmp(&b.0.to_lowercase()))
    });
    let arr: Vec<serde_json::Value> = out
        .iter()
        .map(|(n, p, d, s)| json!({"name": n, "path": p, "is_dir": d, "size": s}))
        .collect();
    respond_json(
        request,
        200,
        json!({"query": q, "count": arr.len(), "entries": arr}),
    )
}

fn respond_download(request: Request, root: &Path, rel: &str) -> u16 {
    let path = match resolve(root, rel) {
        Some(p) => p,
        None => return respond_text(request, 400, "text/plain; charset=utf-8", "bad path"),
    };
    let meta = match fs::metadata(&path) {
        Ok(m) => m,
        Err(_) => return respond_text(request, 404, "text/plain; charset=utf-8", "not found"),
    };
    if !meta.is_file() {
        return respond_text(request, 404, "text/plain; charset=utf-8", "not a file");
    }
    let total = meta.len();

    let mut range_hdr: Option<String> = None;
    for h in request.headers() {
        if h.field.equiv("Range") {
            range_hdr = Some(h.value.as_str().to_string());
        }
    }

    let filename = path
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());
    let encoded = utf8_percent_encode(&filename, NON_ALPHANUMERIC).to_string();

    let mut file = match File::open(&path) {
        Ok(f) => f,
        Err(_) => return respond_text(request, 500, "text/plain; charset=utf-8", "open error"),
    };

    let ctype_header =
        Header::from_bytes(&b"Content-Type"[..], &b"application/octet-stream"[..]).unwrap();
    let accept_header = Header::from_bytes(&b"Accept-Ranges"[..], &b"bytes"[..]).unwrap();
    let disp_value = format!("attachment; filename*=UTF-8''{}", encoded);
    let disp_header = Header::from_bytes(&b"Content-Disposition"[..], disp_value.as_bytes()).unwrap();

    if let Some((start, end)) = range_hdr.as_deref().and_then(|r| parse_range(r, total)) {
        if start > end || start >= total {
            return respond_text(request, 416, "text/plain; charset=utf-8", "range not satisfiable");
        }
        let chunk = end - start + 1;
        if file.seek(SeekFrom::Start(start)).is_err() {
            return respond_text(request, 500, "text/plain; charset=utf-8", "seek error");
        }
        let reader = file.take(chunk);
        let cr = format!("bytes {}-{}/{}", start, end, total);
        let cr_header = Header::from_bytes(&b"Content-Range"[..], cr.as_bytes()).unwrap();
        let response = Response::new(
            StatusCode(206),
            vec![ctype_header, accept_header, disp_header, cr_header],
            reader,
            Some(chunk as usize),
            None,
        );
        let _ = request.respond(response);
        206
    } else {
        let response = Response::new(
            StatusCode(200),
            vec![ctype_header, accept_header, disp_header],
            file,
            Some(total as usize),
            None,
        );
        let _ = request.respond(response);
        200
    }
}

fn parse_range(header: &str, total: u64) -> Option<(u64, u64)> {
    let spec = header.trim().strip_prefix("bytes=")?;
    let spec = spec.split(',').next()?.trim();
    let (s, e) = spec.split_once('-')?;
    if s.is_empty() {
        let n: u64 = e.trim().parse().ok()?;
        if n == 0 {
            return None;
        }
        let start = total.saturating_sub(n);
        return Some((start, total.saturating_sub(1)));
    }
    let start: u64 = s.trim().parse().ok()?;
    let end = if e.trim().is_empty() {
        total.saturating_sub(1)
    } else {
        let end: u64 = e.trim().parse().ok()?;
        end.min(total.saturating_sub(1))
    };
    Some((start, end))
}

fn query_get(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        let (k, v) = match pair.split_once('=') {
            Some(kv) => kv,
            None => continue,
        };
        if k == key {
            let replaced = v.replace('+', " ");
            let decoded = percent_decode_str(&replaced).decode_utf8_lossy().to_string();
            return Some(decoded);
        }
    }
    None
}

fn resolve(root: &Path, rel: &str) -> Option<PathBuf> {
    let mut p = root.to_path_buf();
    for comp in Path::new(rel).components() {
        match comp {
            Component::Normal(c) => p.push(c),
            Component::CurDir => {}
            _ => return None,
        }
    }
    Some(p)
}
