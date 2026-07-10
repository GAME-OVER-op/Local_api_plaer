// media-server — простой файловый HTTP-сервер.
//
// Запускается на рутованном Android-устройстве, читает каталог (по умолчанию
// /storage/emulated/0) и отдаёт по локальной сети:
//   GET /               — проверка живости
//   GET /health         — то же самое
//   GET /list?path=REL  — JSON со списком файлов и папок
//   GET /download?path=REL — отдача файла с поддержкой HTTP Range (перемотка/докачка)
//
// Сеть считается доверенной: авторизации нет.

use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use tiny_http::{Header, Method, Response, Server, StatusCode};

fn main() {
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
            "--host" | "-H" => {
                if i + 1 < args.len() {
                    host = args[i + 1].clone();
                    i += 1;
                }
            }
            "--port" | "-p" => {
                if i + 1 < args.len() {
                    if let Ok(v) = args[i + 1].parse() {
                        port = v;
                    }
                    i += 1;
                }
            }
            "--root" | "-r" => {
                if i + 1 < args.len() {
                    root = args[i + 1].clone();
                    i += 1;
                }
            }
            "--help" | "-h" => {
                print_help();
                return;
            }
            other => eprintln!("Неизвестный аргумент: {}", other),
        }
        i += 1;
    }

    let root_canon = fs::canonicalize(&root).unwrap_or_else(|_| PathBuf::from(&root));
    let addr = format!("{}:{}", host, port);

    let server = match Server::http(addr.as_str()) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Не удалось запустить сервер на {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    println!("media-server слушает на http://{}", addr);
    println!("Корневой каталог: {}", root_canon.display());

    for request in server.incoming_requests() {
        let is_get = *request.method() == Method::Get;
        let url = request.url().to_string();
        let (path_part, query) = match url.split_once('?') {
            Some((p, q)) => (p.to_string(), q.to_string()),
            None => (url.clone(), String::new()),
        };

        if !is_get {
            respond_text(request, 405, "Method Not Allowed");
            continue;
        }

        match path_part.as_str() {
            "/" | "/health" => respond_text(request, 200, "media-server ok"),
            "/list" => handle_list(request, &root_canon, &query),
            "/download" => handle_download(request, &root_canon, &query),
            _ => respond_text(request, 404, "Not Found"),
        }
    }
}

fn print_help() {
    println!("media-server — простой файловый HTTP-сервер для планшета-плеера");
    println!();
    println!("Использование: media-server [ОПЦИИ]");
    println!("  -H, --host <HOST>   Адрес прослушивания (по умолчанию 0.0.0.0)");
    println!("  -p, --port <PORT>   Порт (по умолчанию 10930)");
    println!("  -r, --root <PATH>   Корневой каталог (по умолчанию /storage/emulated/0)");
    println!("  -h, --help          Показать эту справку");
    println!();
    println!("Переменные окружения: MEDIA_HOST, MEDIA_PORT, MEDIA_ROOT");
}

/// Достаёт значение query-параметра (с учётом form-кодирования: '+' => пробел).
fn query_get(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        if k == key {
            let spaced = v.replace('+', " ");
            let decoded = percent_decode_str(&spaced).decode_utf8_lossy().to_string();
            return Some(decoded);
        }
    }
    None
}

/// Безопасно разрешает путь относительно корня. Возвращает None при выходе за корень.
fn resolve(root: &Path, req_path: &str) -> Option<PathBuf> {
    let candidate = if req_path.is_empty() {
        root.to_path_buf()
    } else {
        let rp = Path::new(req_path);
        if rp.is_absolute() {
            rp.to_path_buf()
        } else {
            root.join(rp)
        }
    };
    let canon = fs::canonicalize(&candidate).ok()?;
    if canon.starts_with(root) {
        Some(canon)
    } else {
        None
    }
}

fn respond_text(request: tiny_http::Request, code: u16, body: &str) {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"text/plain; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(body)
        .with_status_code(code)
        .with_header(header);
    let _ = request.respond(response);
}

fn respond_json(request: tiny_http::Request, code: u16, body: String) {
    let header =
        Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(body)
        .with_status_code(code)
        .with_header(header);
    let _ = request.respond(response);
}

fn handle_list(request: tiny_http::Request, root: &Path, query: &str) {
    let req_path = query_get(query, "path").unwrap_or_default();
    let dir = match resolve(root, &req_path) {
        Some(p) => p,
        None => {
            respond_json(request, 403, r#"{"error":"forbidden"}"#.to_string());
            return;
        }
    };
    if !dir.is_dir() {
        respond_json(request, 400, r#"{"error":"not a directory"}"#.to_string());
        return;
    }

    let mut items: Vec<serde_json::Value> = Vec::new();
    match fs::read_dir(&dir) {
        Ok(rd) => {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                let meta = e.metadata().ok();
                let is_dir = meta.as_ref().map(|m| m.is_dir()).unwrap_or(false);
                let size = meta.as_ref().map(|m| m.len()).unwrap_or(0);
                items.push(serde_json::json!({"name": name, "is_dir": is_dir, "size": size}));
            }
        }
        Err(e) => {
            respond_json(request, 500, format!(r#"{{"error":"{}"}}"#, e));
            return;
        }
    }

    items.sort_by(|a, b| {
        let ad = a["is_dir"].as_bool().unwrap_or(false);
        let bd = b["is_dir"].as_bool().unwrap_or(false);
        bd.cmp(&ad).then_with(|| {
            a["name"]
                .as_str()
                .unwrap_or("")
                .to_lowercase()
                .cmp(&b["name"].as_str().unwrap_or("").to_lowercase())
        })
    });

    let out = serde_json::json!({
        "path": req_path,
        "abs_path": dir.to_string_lossy(),
        "entries": items,
    });
    respond_json(request, 200, out.to_string());
}

fn handle_download(request: tiny_http::Request, root: &Path, query: &str) {
    let req_path = query_get(query, "path").unwrap_or_default();
    let path = match resolve(root, &req_path) {
        Some(p) => p,
        None => {
            respond_text(request, 403, "Forbidden");
            return;
        }
    };
    if !path.is_file() {
        respond_text(request, 404, "Not Found");
        return;
    }

    let file = match fs::File::open(&path) {
        Ok(f) => f,
        Err(_) => {
            respond_text(request, 500, "Cannot open file");
            return;
        }
    };
    let total = match file.metadata() {
        Ok(m) => m.len(),
        Err(_) => {
            respond_text(request, 500, "Cannot stat file");
            return;
        }
    };

    let ctype = content_type(&path);
    let fname = path
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default();
    let ct = Header::from_bytes(&b"Content-Type"[..], ctype.as_bytes()).unwrap();
    let ar = Header::from_bytes(&b"Accept-Ranges"[..], &b"bytes"[..]).unwrap();
    let cd_value = format!(
        "attachment; filename*=UTF-8''{}",
        utf8_percent_encode(&fname, NON_ALPHANUMERIC)
    );
    let cd = Header::from_bytes(&b"Content-Disposition"[..], cd_value.as_bytes()).unwrap();

    let range = request
        .headers()
        .iter()
        .find(|h| h.field.equiv("Range"))
        .map(|h| h.value.as_str().to_string());

    if let Some(r) = range {
        if let Some((start, end)) = parse_range(&r, total) {
            let len = end - start + 1;
            let mut f = file;
            if f.seek(SeekFrom::Start(start)).is_err() {
                respond_text(request, 500, "Seek error");
                return;
            }
            let reader = f.take(len);
            let cr = Header::from_bytes(
                &b"Content-Range"[..],
                format!("bytes {}-{}/{}", start, end, total).as_bytes(),
            )
            .unwrap();
            let response = Response::new(
                StatusCode(206),
                vec![ct, ar, cr, cd],
                reader,
                Some(len as usize),
                None,
            );
            let _ = request.respond(response);
            return;
        }
    }

    let response = Response::new(
        StatusCode(200),
        vec![ct, ar, cd],
        file,
        Some(total as usize),
        None,
    );
    let _ = request.respond(response);
}

/// Разбирает заголовок Range вида "bytes=START-END" (или суффиксный "bytes=-N").
fn parse_range(header: &str, total: u64) -> Option<(u64, u64)> {
    let bytes = header.trim().strip_prefix("bytes=")?;
    let (s, e) = bytes.split_once('-')?;
    let s = s.trim();
    let e = e.trim();

    if s.is_empty() {
        let n: u64 = e.parse().ok()?;
        if n == 0 || total == 0 {
            return None;
        }
        let start = total.saturating_sub(n);
        return Some((start, total - 1));
    }

    let start: u64 = s.parse().ok()?;
    if start >= total {
        return None;
    }
    let end: u64 = if e.is_empty() {
        total - 1
    } else {
        e.parse::<u64>().ok()?.min(total - 1)
    };
    if start > end {
        return None;
    }
    Some((start, end))
}

fn content_type(path: &Path) -> &'static str {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();
    match ext.as_str() {
        "mp4" | "m4v" => "video/mp4",
        "mkv" => "video/x-matroska",
        "webm" => "video/webm",
        "avi" => "video/x-msvideo",
        "mov" => "video/quicktime",
        "flv" => "video/x-flv",
        "ts" => "video/mp2t",
        "wmv" => "video/x-ms-wmv",
        "3gp" => "video/3gpp",
        "mp3" => "audio/mpeg",
        "m4a" | "aac" => "audio/aac",
        "flac" => "audio/flac",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "gif" => "image/gif",
        "srt" => "application/x-subrip",
        "pdf" => "application/pdf",
        "txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}
