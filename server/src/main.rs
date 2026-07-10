// media-server — лёгкий многопоточный файловый HTTP-сервер.
// Отдаёт список файлов, поиск и сами файлы (с поддержкой Range) по локальной сети.

use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Component, Path, PathBuf};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread;

use percent_encoding::percent_decode_str;
use serde_json::json;
use tiny_http::{Header, Method, Request, Response, Server};

fn main() {
    let mut host = std::env::var("MEDIA_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let mut port: u16 = std::env::var("MEDIA_PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(10930);
    let mut root = std::env::var("MEDIA_ROOT").unwrap_or_else(|_| "/storage/emulated/0".to_string());

    // Разбор аргументов командной строки.
    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
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
            "-h" | "--help" => {
                print_help();
                return;
            }
            other => {
                eprintln!("Неизвестный аргумент: {}", other);
                print_help();
                return;
            }
        }
        i += 1;
    }

    let root = Arc::new(PathBuf::from(root));
    let addr = format!("{}:{}", host, port);

    let server = match Server::http(&addr) {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("Не удалось запустить сервер на {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    println!("media-server слушает на http://{}", addr);
    println!("Корневой каталог: {}", root.display());

    // Пул воркеров: libVLC держит несколько соединений одновременно
    // (поток воспроизведения + Range-запросы на перемотку), поэтому одного потока мало.
    let workers = thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .max(4);
    println!("Потоков-обработчиков: {}", workers);

    let (tx, rx) = mpsc::channel::<Request>();
    let rx = Arc::new(std::sync::Mutex::new(rx));

    let mut handles = Vec::new();
    for _ in 0..workers {
        let rx = Arc::clone(&rx);
        let root = Arc::clone(&root);
        handles.push(thread::spawn(move || loop {
            let req = {
                let lock = rx.lock().unwrap();
                lock.recv()
            };
            match req {
                Ok(request) => handle(request, &root),
                Err(_) => break,
            }
        }));
    }

    for request in server.incoming_requests() {
        if tx.send(request).is_err() {
            break;
        }
    }

    drop(tx);
    for h in handles {
        let _ = h.join();
    }
}

fn print_help() {
    println!("media-server — файловый HTTP-сервер");
    println!("Использование: media-server [ОПЦИИ]");
    println!("  -H, --host <HOST>   адрес прослушивания (по умолчанию 0.0.0.0)");
    println!("  -p, --port <PORT>   порт (по умолчанию 10930)");
    println!("  -r, --root <PATH>   корневой каталог (по умолчанию /storage/emulated/0)");
    println!("  -h, --help          эта справка");
}

fn handle(request: Request, root: &Path) {
    let method_str = format!("{:?}", request.method());
    let url = request.url().to_string();
    let status = route(request, root);
    println!("{} {} -> {}", method_str, url, status);
}

fn route(request: Request, root: &Path) -> u16 {
    let url = request.url().to_string();
    let (path_part, query) = match url.split_once('?') {
        Some((p, q)) => (p, q),
        None => (url.as_str(), ""),
    };

    if request.method() != &Method::Get {
        return respond_text(request, 405, "method not allowed");
    }

    match path_part {
        "/" | "/health" => respond_text(request, 200, "media-server ok"),
        "/list" => handle_list(request, root, query),
        "/search" => handle_search(request, root, query),
        "/download" => handle_download(request, root, query),
        _ => respond_text(request, 404, "not found"),
    }
}

// Извлечь параметр из query-строки: '+' → пробел, затем percent-decode.
fn query_get(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        let (k, v) = match pair.split_once('=') {
            Some(kv) => kv,
            None => (pair, ""),
        };
        if k == key {
            let replaced = v.replace('+', " ");
            let decoded = percent_decode_str(&replaced).decode_utf8_lossy().to_string();
            return Some(decoded);
        }
    }
    None
}

// Безопасное соединение относительного пути с корнем (защита от path traversal).
fn resolve(root: &Path, rel: &str) -> Option<PathBuf> {
    let rel = rel.trim_start_matches('/');
    let mut result = root.to_path_buf();
    for comp in Path::new(rel).components() {
        match comp {
            Component::Normal(c) => result.push(c),
            Component::CurDir => {}
            _ => return None, // Отклоняем '..', корневые и префиксные компоненты.
        }
    }
    Some(result)
}

fn handle_list(request: Request, root: &Path, query: &str) -> u16 {
    let rel = query_get(query, "path").unwrap_or_default();
    let dir = match resolve(root, &rel) {
        Some(p) => p,
        None => return respond_json(request, 400, &json!({"error": "bad path"})),
    };

    let read_dir = match fs::read_dir(&dir) {
        Ok(rd) => rd,
        Err(e) => {
            return respond_json(request, 404, &json!({"error": format!("{}", e)}));
        }
    };

    let mut entries: Vec<serde_json::Value> = Vec::new();
    for entry in read_dir.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let is_dir = meta.is_dir();
        let size = if is_dir { 0 } else { meta.len() };
        entries.push(json!({"name": name, "is_dir": is_dir, "size": size}));
    }

    // Сначала папки, потом файлы; внутри — по имени без учёта регистра.
    entries.sort_by(|a, b| {
        let ad = a["is_dir"].as_bool().unwrap_or(false);
        let bd = b["is_dir"].as_bool().unwrap_or(false);
        bd.cmp(&ad).then_with(|| {
            let an = a["name"].as_str().unwrap_or("").to_lowercase();
            let bn = b["name"].as_str().unwrap_or("").to_lowercase();
            an.cmp(&bn)
        })
    });

    let body = json!({
        "path": rel,
        "abs_path": dir.display().to_string(),
        "entries": entries,
    });
    respond_json(request, 200, &body)
}

fn handle_search(request: Request, root: &Path, query: &str) -> u16 {
    let q = query_get(query, "q").unwrap_or_default();
    let q_trim = q.trim().to_lowercase();
    if q_trim.is_empty() {
        return respond_json(request, 400, &json!({"error": "empty query"}));
    }
    let base_rel = query_get(query, "path").unwrap_or_default();
    let base = match resolve(root, &base_rel) {
        Some(p) => p,
        None => return respond_json(request, 400, &json!({"error": "bad path"})),
    };

    let mut results: Vec<serde_json::Value> = Vec::new();
    let limit = 500usize;
    let mut stack: Vec<PathBuf> = vec![base.clone()];
    while let Some(dir) = stack.pop() {
        if results.len() >= limit {
            break;
        }
        let rd = match fs::read_dir(&dir) {
            Ok(rd) => rd,
            Err(_) => continue,
        };
        for entry in rd.flatten() {
            let meta = match entry.symlink_metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.file_type().is_symlink() {
                continue; // Не ходим по симлинкам (защита от циклов).
            }
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let is_dir = meta.is_dir();
            if is_dir {
                stack.push(path.clone());
            }
            if name.to_lowercase().contains(&q_trim) {
                let rel_path = path
                    .strip_prefix(root)
                    .unwrap_or(&path)
                    .to_string_lossy()
                    .to_string();
                let size = if is_dir { 0 } else { meta.len() };
                results.push(json!({
                    "name": name,
                    "path": rel_path,
                    "is_dir": is_dir,
                    "size": size,
                }));
                if results.len() >= limit {
                    break;
                }
            }
        }
    }

    // Папки выше, затем по имени.
    results.sort_by(|a, b| {
        let ad = a["is_dir"].as_bool().unwrap_or(false);
        let bd = b["is_dir"].as_bool().unwrap_or(false);
        bd.cmp(&ad).then_with(|| {
            let an = a["name"].as_str().unwrap_or("").to_lowercase();
            let bn = b["name"].as_str().unwrap_or("").to_lowercase();
            an.cmp(&bn)
        })
    });

    let body = json!({
        "query": q,
        "count": results.len(),
        "entries": results,
    });
    respond_json(request, 200, &body)
}

fn handle_download(request: Request, root: &Path, query: &str) -> u16 {
    let rel = match query_get(query, "path") {
        Some(p) => p,
        None => return respond_text(request, 400, "missing path"),
    };
    let file_path = match resolve(root, &rel) {
        Some(p) => p,
        None => return respond_text(request, 400, "bad path"),
    };

    let meta = match fs::metadata(&file_path) {
        Ok(m) => m,
        Err(_) => return respond_text(request, 404, "not found"),
    };
    if meta.is_dir() {
        return respond_text(request, 400, "is a directory");
    }
    let total = meta.len();

    let mut file = match fs::File::open(&file_path) {
        Ok(f) => f,
        Err(_) => return respond_text(request, 500, "open failed"),
    };

    // Разбор заголовка Range (перемотка/докачка): "bytes=START-END".
    let range = request.headers().iter().find_map(|h| {
        if h.field.equiv("Range") {
            Some(h.value.as_str().to_string())
        } else {
            None
        }
    });

    let file_name = file_path
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());
    let encoded_name: String = percent_encoding::utf8_percent_encode(
        &file_name,
        percent_encoding::NON_ALPHANUMERIC,
    )
    .to_string();
    let disposition = format!(
        "attachment; filename*=UTF-8''{}",
        encoded_name
    );

    if let Some(range) = range {
        if let Some((start, end)) = parse_range(&range, total) {
            let length = end - start + 1;
            if file.seek(SeekFrom::Start(start)).is_err() {
                return respond_text(request, 500, "seek failed");
            }
            let reader = file.take(length);
            let mut response = Response::new(
                tiny_http::StatusCode(206),
                vec![
                    header("Content-Type", "application/octet-stream"),
                    header("Accept-Ranges", "bytes"),
                    header(
                        "Content-Range",
                        &format!("bytes {}-{}/{}", start, end, total),
                    ),
                    header("Content-Disposition", &disposition),
                ],
                reader,
                Some(length as usize),
                None,
            );
            let _ = response_add_no_op(&mut response);
            let _ = request.respond(response);
            return 206;
        }
    }

    // Полная отдача файла.
    let response = Response::new(
        tiny_http::StatusCode(200),
        vec![
            header("Content-Type", "application/octet-stream"),
            header("Accept-Ranges", "bytes"),
            header("Content-Disposition", &disposition),
        ],
        file,
        Some(total as usize),
        None,
    );
    let _ = request.respond(response);
    200
}

fn response_add_no_op<R>(_r: &mut Response<R>) -> bool {
    true
}

fn parse_range(range: &str, total: u64) -> Option<(u64, u64)> {
    let range = range.trim();
    let bytes = range.strip_prefix("bytes=")?;
    let (start_s, end_s) = bytes.split_once('-')?;
    if start_s.is_empty() {
        // Суффикс: последние N байт.
        let n: u64 = end_s.parse().ok()?;
        if n == 0 || total == 0 {
            return None;
        }
        let start = total.saturating_sub(n);
        return Some((start, total - 1));
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
    let end = end.min(total - 1);
    Some((start, end))
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).unwrap()
}

fn respond_text(request: Request, status: u16, text: &str) -> u16 {
    let response = Response::from_string(text)
        .with_status_code(status)
        .with_header(header("Content-Type", "text/plain; charset=utf-8"));
    let _ = request.respond(response);
    status
}

fn respond_json(request: Request, status: u16, value: &serde_json::Value) -> u16 {
    let body = value.to_string();
    let response = Response::from_string(body)
        .with_status_code(status)
        .with_header(header("Content-Type", "application/json; charset=utf-8"));
    let _ = request.respond(response);
    status
}
