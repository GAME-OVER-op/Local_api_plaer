use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use serde_json::{json, Value};
use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::net::UdpSocket;
use std::path::{Component, Path, PathBuf};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tiny_http::{Header, Request, Response, Server, StatusCode};

struct Config {
    host: String,
    port: u16,
    root: PathBuf,
    name: String,
    background: bool,
}

struct State {
    config: Config,
    approved: Mutex<Vec<Value>>,
    allowed_path: PathBuf,
}

struct ApprovalReq {
    id: String,
    name: String,
    ip: String,
    resp: Sender<bool>,
}

fn parse_config() -> Config {
    let mut host = std::env::var("MEDIA_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let mut port: u16 = std::env::var("MEDIA_PORT").ok().and_then(|s| s.parse().ok()).unwrap_or(10930);
    let mut root = std::env::var("MEDIA_ROOT").unwrap_or_else(|_| "/storage/emulated/0".to_string());
    let mut name = std::env::var("MEDIA_NAME").unwrap_or_else(|_| "media-server".to_string());
    let mut background = std::env::var("MEDIA_BACKGROUND").ok().as_deref() == Some("1");

    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "-h" | "--help" => { print_help(); std::process::exit(0); }
            "-b" | "--background" => { background = true; }
            "-H" | "--host" => { if i + 1 < args.len() { host = args[i + 1].clone(); i += 1; } }
            "-p" | "--port" => { if i + 1 < args.len() { if let Ok(p) = args[i + 1].parse() { port = p; } i += 1; } }
            "-r" | "--root" => { if i + 1 < args.len() { root = args[i + 1].clone(); i += 1; } }
            "-n" | "--name" => { if i + 1 < args.len() { name = args[i + 1].clone(); i += 1; } }
            _ => {}
        }
        i += 1;
    }

    Config { host, port, root: PathBuf::from(root), name, background }
}

fn print_help() {
    println!("media-server — файловый HTTP-сервер с подтверждением доступа");
    println!();
    println!("Использование: media-server [опции]");
    println!("  -H, --host <адрес>   адрес прослушивания (0.0.0.0)");
    println!("  -p, --port <порт>    порт (10930)");
    println!("  -r, --root <путь>    корневая папка (/storage/emulated/0)");
    println!("  -n, --name <имя>     имя сервера для автообнаружения");
    println!("  -b, --background     фоновый режим: пускать только из allowed.json");
    println!("  -h, --help           эта справка");
    println!();
    println!("Команды консоли: list, revoke <id|имя>, help");
}

fn load_allowed(path: &Path) -> Vec<Value> {
    match fs::read_to_string(path) {
        Ok(s) => serde_json::from_str::<Value>(&s)
            .ok()
            .and_then(|v| v.as_array().cloned())
            .unwrap_or_default(),
        Err(_) => Vec::new(),
    }
}

fn save_allowed(path: &Path, list: &[Value]) {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let v = Value::Array(list.to_vec());
    let _ = fs::write(path, serde_json::to_string_pretty(&v).unwrap_or_default());
}

fn is_approved(approved: &Mutex<Vec<Value>>, id: &str) -> bool {
    if id.is_empty() {
        return false;
    }
    let g = approved.lock().unwrap();
    g.iter().any(|r| r.get("id").and_then(|x| x.as_str()) == Some(id))
}

fn approve(approved: &Mutex<Vec<Value>>, path: &Path, id: &str, name: &str) {
    let mut g = approved.lock().unwrap();
    if g.iter().any(|r| r.get("id").and_then(|x| x.as_str()) == Some(id)) {
        return;
    }
    let ts = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    g.push(json!({ "id": id, "name": name, "approved_at": ts }));
    save_allowed(path, g.as_slice());
}

fn handle_command(cmd: &str, approved: &Mutex<Vec<Value>>, path: &Path) {
    let c = cmd.trim();
    if c.is_empty() {
        return;
    }
    if c == "list" {
        let g = approved.lock().unwrap();
        if g.is_empty() {
            println!("Список пуст.");
        } else {
            for r in g.iter() {
                let id = r.get("id").and_then(|x| x.as_str()).unwrap_or("");
                let name = r.get("name").and_then(|x| x.as_str()).unwrap_or("");
                println!("  {} — {}", name, id);
            }
        }
    } else if let Some(arg) = c.strip_prefix("revoke ") {
        let arg = arg.trim();
        let mut g = approved.lock().unwrap();
        let before = g.len();
        g.retain(|r| {
            r.get("id").and_then(|x| x.as_str()) != Some(arg)
                && r.get("name").and_then(|x| x.as_str()) != Some(arg)
        });
        let removed = before - g.len();
        save_allowed(path, g.as_slice());
        println!("Отозвано записей: {}", removed);
    } else if c == "help" {
        println!("Команды: list, revoke <id|имя>, help");
    } else {
        println!("Неизвестная команда: {} (help — список)", c);
    }
}

fn control_loop(rx: mpsc::Receiver<ApprovalReq>, approved: Arc<Mutex<Vec<Value>>>, path: PathBuf) {
    let (line_tx, line_rx) = mpsc::channel::<String>();
    thread::spawn(move || {
        let stdin = io::stdin();
        loop {
            let mut line = String::new();
            match stdin.read_line(&mut line) {
                Ok(0) => break,
                Ok(_) => {
                    if line_tx.send(line.trim().to_string()).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    loop {
        match rx.recv_timeout(Duration::from_millis(400)) {
            Ok(req) => {
                if is_approved(&approved, &req.id) {
                    let _ = req.resp.send(true);
                    continue;
                }
                while line_rx.try_recv().is_ok() {}
                println!();
                println!("=== Запрос доступа ===");
                println!("Устройство: \"{}\"  (IP {}, id {})", req.name, req.ip, req.id);
                print!("Разрешить? [y/n] (30 сек): ");
                let _ = io::stdout().flush();
                match line_rx.recv_timeout(Duration::from_secs(30)) {
                    Ok(line) => {
                        let t = line.trim().to_lowercase();
                        if t == "y" || t == "yes" || t == "да" || t == "д" {
                            approve(&approved, &path, &req.id, &req.name);
                            println!("Разрешено: {}", req.name);
                            let _ = req.resp.send(true);
                        } else {
                            println!("Отклонено: {}", req.name);
                            let _ = req.resp.send(false);
                        }
                    }
                    Err(_) => {
                        println!("Время вышло — отказано: {}", req.name);
                        let _ = req.resp.send(false);
                    }
                }
            }
            Err(RecvTimeoutError::Timeout) => {
                while let Ok(cmd) = line_rx.try_recv() {
                    handle_command(&cmd, &approved, &path);
                }
            }
            Err(RecvTimeoutError::Disconnected) => break,
        }
    }
}

fn spawn_discovery(name: String, port: u16) {
    thread::spawn(move || {
        let sock = match UdpSocket::bind(("0.0.0.0", port)) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("Автообнаружение недоступно (UDP {}): {}", port, e);
                return;
            }
        };
        let mut buf = [0u8; 1024];
        loop {
            match sock.recv_from(&mut buf) {
                Ok((n, src)) => {
                    let msg = String::from_utf8_lossy(&buf[..n]);
                    if msg.trim_start().starts_with("MEDIA_DISCOVER") {
                        let reply = json!({ "app": "media-server", "name": name, "port": port }).to_string();
                        let _ = sock.send_to(reply.as_bytes(), src);
                    }
                }
                Err(_) => break,
            }
        }
    });
}

fn main() {
    let config = parse_config();
    let allowed_path = PathBuf::from("/data/adb/api_plaer/allowed.json");
    let approved = Arc::new(Mutex::new(load_allowed(&allowed_path)));

    let addr = format!("{}:{}", config.host, config.port);
    let server = match Server::http(addr.as_str()) {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("Не удалось запустить сервер на {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    println!("media-server: http://{}", addr);
    println!("Корневая папка: {}", config.root.display());
    println!("Имя сервера: {}", config.name);
    println!("Разрешённых устройств: {}", approved.lock().unwrap().len());

    spawn_discovery(config.name.clone(), config.port);

    let approval_tx: Option<Sender<ApprovalReq>> = if config.background {
        println!("Фоновый режим: пускаются только устройства из allowed.json.");
        None
    } else {
        let (tx, rx) = mpsc::channel::<ApprovalReq>();
        let approved_c = Arc::clone(&approved);
        let path_c = allowed_path.clone();
        thread::spawn(move || control_loop(rx, approved_c, path_c));
        println!("Подтверждайте доступ в этой консоли (y/n). Команды: list, revoke <id|имя>.");
        Some(tx)
    };

    let state = Arc::new(State { config, approved: Mutex::new(load_allowed(&allowed_path)), allowed_path: allowed_path.clone() });
    // share the same approved list between control thread and handlers
    let shared_approved = Arc::clone(&approved);

    let workers = thread::available_parallelism().map(|n| n.get()).unwrap_or(4).max(4);
    let mut handles = Vec::new();
    for _ in 0..workers {
        let server = Arc::clone(&server);
        let state = Arc::clone(&state);
        let approved_w = Arc::clone(&shared_approved);
        let atx = approval_tx.clone();
        handles.push(thread::spawn(move || loop {
            match server.recv() {
                Ok(request) => handle(request, &state, &approved_w, &atx),
                Err(_) => break,
            }
        }));
    }
    for h in handles {
        let _ = h.join();
    }
}

fn header_val(request: &Request, name: &str) -> String {
    for h in request.headers() {
        if h.field.equiv(name) {
            return h.value.as_str().to_string();
        }
    }
    String::new()
}

fn authorize(
    approved: &Arc<Mutex<Vec<Value>>>,
    approval_tx: &Option<Sender<ApprovalReq>>,
    id: &str,
    name: &str,
    ip: &str,
) -> bool {
    if is_approved(approved, id) {
        return true;
    }
    match approval_tx {
        None => false,
        Some(tx) => {
            let (rtx, rrx) = mpsc::channel();
            let req = ApprovalReq { id: id.to_string(), name: name.to_string(), ip: ip.to_string(), resp: rtx };
            if tx.send(req).is_err() {
                return false;
            }
            rrx.recv_timeout(Duration::from_secs(35)).unwrap_or(false)
        }
    }
}

fn handle(
    request: Request,
    state: &Arc<State>,
    approved: &Arc<Mutex<Vec<Value>>>,
    approval_tx: &Option<Sender<ApprovalReq>>,
) {
    let url = request.url().to_string();
    let method = format!("{:?}", request.method());
    let (path_part, query_part) = match url.find('?') {
        Some(idx) => (url[..idx].to_string(), url[idx + 1..].to_string()),
        None => (url.clone(), String::new()),
    };

    if path_part == "/" || path_part == "/health" {
        let s = respond_text(request, 200, "media-server ok");
        println!("{} {} -> {}", method, url, s);
        return;
    }

    let mut id = header_val(&request, "X-Device-Id");
    let mut name = header_val(&request, "X-Device-Name");
    let ip = request.remote_addr().map(|a| a.ip().to_string()).unwrap_or_default();
    if id.is_empty() {
        id = query_get(&query_part, "dev").unwrap_or_default();
    }
    if name.is_empty() {
        name = query_get(&query_part, "dn").unwrap_or_default();
    }
    if id.is_empty() {
        id = format!("ip:{}", ip);
    }
    if name.is_empty() {
        name = "неизвестное устройство".to_string();
    }

    if !authorize(approved, approval_tx, &id, &name, &ip) {
        let s = respond_text(request, 403, "access denied");
        println!("{} {} -> {} ({})", method, url, s, name);
        return;
    }

    let s = if path_part == "/list" {
        let rel = query_get(&query_part, "path").unwrap_or_default();
        respond_list(request, &state.config, &rel)
    } else if path_part == "/search" {
        let q = query_get(&query_part, "q").unwrap_or_default();
        let rel = query_get(&query_part, "path").unwrap_or_default();
        respond_search(request, &state.config, &q, &rel)
    } else if path_part == "/download" {
        let rel = query_get(&query_part, "path").unwrap_or_default();
        respond_download(request, &state.config, &rel)
    } else {
        respond_text(request, 404, "not found")
    };
    println!("{} {} -> {}", method, url, s);
}

fn query_get(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        let mut it = pair.splitn(2, '=');
        let k = it.next().unwrap_or("");
        if k == key {
            let v = it.next().unwrap_or("");
            let replaced = v.replace('+', " ");
            return Some(percent_decode_str(&replaced).decode_utf8_lossy().to_string());
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
    let response = Response::from_string(body).with_status_code(StatusCode(code)).with_header(header);
    let _ = request.respond(response);
    code
}

fn respond_json(request: Request, code: u16, value: Value) -> u16 {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(value.to_string()).with_status_code(StatusCode(code)).with_header(header);
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
            let meta = match entry.metadata() { Ok(m) => m, Err(_) => continue };
            if meta.file_type().is_symlink() { continue; }
            let name = entry.file_name().to_string_lossy().to_string();
            let is_dir = meta.is_dir();
            let size = if is_dir { 0 } else { meta.len() };
            list.push((name, is_dir, size));
        }
    }
    list.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    let entries: Vec<Value> = list.iter()
        .map(|(name, is_dir, size)| json!({ "name": name, "is_dir": is_dir, "size": size }))
        .collect();
    respond_json(request, 200, json!({ "path": rel, "abs_path": dir.display().to_string(), "entries": entries }))
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
        if results.len() >= limit { break; }
        let read = match fs::read_dir(&current) { Ok(r) => r, Err(_) => continue };
        for item in read {
            let entry = match item { Ok(e) => e, Err(_) => continue };
            let meta = match entry.metadata() { Ok(m) => m, Err(_) => continue };
            if meta.file_type().is_symlink() { continue; }
            let name = entry.file_name().to_string_lossy().to_string();
            let full = entry.path();
            let is_dir = meta.is_dir();
            if is_dir { stack.push(full.clone()); }
            if !needle.is_empty() && name.to_lowercase().contains(&needle) {
                let relpath = full.strip_prefix(&config.root).unwrap_or(&full).to_string_lossy().to_string();
                let size = if is_dir { 0 } else { meta.len() };
                results.push((name, is_dir, size, relpath));
                if results.len() >= limit { break; }
            }
        }
    }
    results.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    let entries: Vec<Value> = results.iter()
        .map(|(name, is_dir, size, path)| json!({ "name": name, "is_dir": is_dir, "size": size, "path": path }))
        .collect();
    respond_json(request, 200, json!({ "query": query, "count": entries.len(), "entries": entries }))
}

fn parse_range(range: &str, total: u64) -> Option<(u64, u64)> {
    let range = range.trim();
    if !range.starts_with("bytes=") { return None; }
    let spec = &range[6..];
    let mut parts = spec.splitn(2, '-');
    let start_s = parts.next().unwrap_or("");
    let end_s = parts.next().unwrap_or("");
    if start_s.is_empty() {
        let suffix: u64 = end_s.parse().ok()?;
        if suffix == 0 || total == 0 { return None; }
        let suffix = suffix.min(total);
        return Some((total - suffix, total - 1));
    }
    let start: u64 = start_s.parse().ok()?;
    let end: u64 = if end_s.is_empty() { total - 1 } else { end_s.parse().ok()? };
    if start > end || start >= total { return None; }
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
    if !meta.is_file() { return respond_text(request, 400, "not a file"); }
    let total = meta.len();

    let mut range_header: Option<String> = None;
    for h in request.headers() {
        if h.field.equiv("Range") { range_header = Some(h.value.as_str().to_string()); }
    }

    let filename = path.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "file".to_string());
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
            let response = Response::new(StatusCode(206), vec![ct, cd, ar, cr], reader, Some(length as usize), None);
            let _ = request.respond(response);
            return 206;
        }
    }

    let response = Response::new(StatusCode(200), vec![ct, cd, ar], file, Some(total as usize), None);
    let _ = request.respond(response);
    200
}
