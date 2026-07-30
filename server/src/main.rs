use hmac::{Hmac, Mac};
use percent_encoding::{percent_decode_str, utf8_percent_encode, NON_ALPHANUMERIC};
use serde_json::{json, Value};
use sha2::Sha256;
use std::collections::{HashMap, VecDeque};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::net::UdpSocket;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tiny_http::{Header, Request, Response, Server, StatusCode};

type HmacSha256 = Hmac<Sha256>;

// Баннер "обманки": так сервер представляется для любых посторонних запросов.
// Ничего не говорит о реальной модели/устройстве. Меняйте по вкусу.
const SERVER_BANNER: &str = "nginx/1.18.0 (Ubuntu)";
const AUTH_WINDOW_SECS: u64 = 600;
const STREAM_AUTH_WINDOW_SECS: u64 = 86_400;
const MAX_ACTIVE_REQUESTS: usize = 32;
const MAX_DOWNLOADS: usize = 4;
const MAX_PENDING_PAIRINGS: usize = 2;
const MAX_SEARCHES: usize = 2;
const MAX_LIST_ENTRIES: usize = 10_000;
const SEARCH_RESULT_LIMIT: usize = 500;
const SEARCH_VISIT_LIMIT: usize = 20_000;
const SEARCH_DEPTH_LIMIT: usize = 32;
const SEARCH_TIME_LIMIT: Duration = Duration::from_secs(5);

const NGINX_WELCOME: &str = "<!DOCTYPE html>\n<html>\n<head>\n<title>Welcome to nginx!</title>\n<style>\nhtml { color-scheme: light dark; }\nbody { width: 35em; margin: 0 auto; font-family: Tahoma, Verdana, Arial, sans-serif; }\n</style>\n</head>\n<body>\n<h1>Welcome to nginx!</h1>\n<p>If you see this page, the nginx web server is successfully installed and\nworking. Further configuration is required.</p>\n\n<p>For online documentation and support please refer to\n<a href=\"http://nginx.org/\">nginx.org</a>.<br/>\nCommercial support is available at\n<a href=\"http://nginx.com/\">nginx.com</a>.</p>\n\n<p><em>Thank you for using nginx.</em></p>\n</body>\n</html>\n";
const NGINX_404: &str = "<html>\r\n<head><title>404 Not Found</title></head>\r\n<body>\r\n<center><h1>404 Not Found</h1></center>\r\n<hr><center>nginx/1.18.0 (Ubuntu)</center>\r\n</body>\r\n</html>\r\n";
const NGINX_403: &str = "<html>\r\n<head><title>403 Forbidden</title></head>\r\n<body>\r\n<center><h1>403 Forbidden</h1></center>\r\n<hr><center>nginx/1.18.0 (Ubuntu)</center>\r\n</body>\r\n</html>\r\n";
const NGINX_503: &str = "<html>\r\n<head><title>503 Service Temporarily Unavailable</title></head>\r\n<body>\r\n<center><h1>503 Service Temporarily Unavailable</h1></center>\r\n<hr><center>nginx/1.18.0 (Ubuntu)</center>\r\n</body>\r\n</html>\r\n";

struct Config {
    host: String,
    port: u16,
    root: PathBuf,
    name: String,
    background: bool,
    data_dir: PathBuf,
}

struct State {
    config: Config,
    knocks_path: PathBuf,
    nonces: Mutex<HashMap<String, VecDeque<(String, u64)>>>,
    downloads: Arc<AtomicUsize>,
    pairings: Arc<AtomicUsize>,
    searches: Arc<AtomicUsize>,
}

struct ApprovalReq {
    id: String,
    name: String,
    ip: String,
    key: String,
    resp: Sender<bool>,
}

struct AuthFields {
    id: String,
    name: String,
    key: String,
    time: String,
    nonce: String,
    proof: String,
    stream: bool,
}

struct SlotGuard {
    counter: Arc<AtomicUsize>,
}

impl Drop for SlotGuard {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::AcqRel);
    }
}

fn acquire_slot(counter: &Arc<AtomicUsize>, max: usize) -> Option<SlotGuard> {
    loop {
        let current = counter.load(Ordering::Acquire);
        if current >= max {
            return None;
        }
        if counter
            .compare_exchange(current, current + 1, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            return Some(SlotGuard { counter: Arc::clone(counter) });
        }
    }
}

fn default_data_dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
}

fn parse_config() -> Config {
    let mut host = std::env::var("MEDIA_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let mut port: u16 = std::env::var("MEDIA_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(10930);
    let mut root = std::env::var("MEDIA_ROOT").unwrap_or_else(|_| "/storage".to_string());
    let mut name = std::env::var("MEDIA_NAME").unwrap_or_else(|_| "media-server".to_string());
    let mut background = std::env::var("MEDIA_BACKGROUND").ok().as_deref() == Some("1");
    let mut data_dir: Option<PathBuf> = std::env::var("MEDIA_DATA_DIR").ok().map(PathBuf::from);

    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            "-b" | "--background" => background = true,
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
            "-d" | "--data" => {
                if i + 1 < args.len() {
                    data_dir = Some(PathBuf::from(args[i + 1].clone()));
                    i += 1;
                }
            }
            "-n" | "--name" => {
                if i + 1 < args.len() {
                    name = args[i + 1].clone();
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
        name,
        background,
        data_dir: data_dir.unwrap_or_else(default_data_dir),
    }
}

fn print_help() {
    println!("media-server — файловый HTTP-сервер с подтверждением доступа и маскировкой");
    println!();
    println!("Использование: media-server [опции]");
    println!("  -H, --host <адрес>   адрес прослушивания (0.0.0.0)");
    println!("  -p, --port <порт>    порт (10930)");
    println!("  -r, --root <путь>    корневая папка (/storage)");
    println!("  -d, --data <путь>    папка для allowed.json/access.log (по умолчанию — папка программы)");
    println!("  -n, --name <имя>     имя сервера для автообнаружения");
    println!("  -b, --background     фоновый режим: пускать только из allowed.json");
    println!("  -h, --help           эта справка");
    println!();
    println!("Для чужих запросов сервер маскируется под: {}", SERVER_BANNER);
    println!("Команды консоли: list, revoke <id|имя>, stats, help");
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

fn approved_key(approved: &Mutex<Vec<Value>>, id: &str) -> Option<String> {
    if id.is_empty() {
        return None;
    }
    let g = approved.lock().unwrap();
    g.iter()
        .find(|r| r.get("id").and_then(|x| x.as_str()) == Some(id))
        .and_then(|r| r.get("key").and_then(|x| x.as_str()))
        .filter(|k| !k.is_empty())
        .map(ToOwned::to_owned)
}

fn approve(approved: &Mutex<Vec<Value>>, path: &Path, id: &str, name: &str, key: &str) {
    let mut g = approved.lock().unwrap();
    g.retain(|r| r.get("id").and_then(|x| x.as_str()) != Some(id));
    let ts = unix_now();
    g.push(json!({ "id": id, "name": name, "key": key, "approved_at": ts }));
    save_allowed(path, g.as_slice());
}

fn handle_command(cmd: &str, approved: &Mutex<Vec<Value>>, allowed: &Path, knocks: &Path) {
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
                let protected = r.get("key").and_then(|x| x.as_str()).map(|x| !x.is_empty()).unwrap_or(false);
                println!("  {} — {}{}", name, id, if protected { "" } else { " (нужна перепривязка)" });
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
        save_allowed(allowed, g.as_slice());
        println!("Отозвано записей: {}", removed);
    } else if c == "stats" {
        print_stats(knocks);
    } else if c == "help" {
        println!("Команды: list, revoke <id|имя>, stats, help");
    } else {
        println!("Неизвестная команда: {} (help — список)", c);
    }
}

fn print_stats(path: &Path) {
    match fs::read_to_string(path) {
        Ok(s) => {
            let lines: Vec<&str> = s.lines().filter(|l| !l.trim().is_empty()).collect();
            println!("Обращений к обманке всего: {}", lines.len());
            let start = lines.len().saturating_sub(10);
            for l in &lines[start..] {
                if let Ok(v) = serde_json::from_str::<Value>(l) {
                    let ts = v.get("ts").and_then(|x| x.as_u64()).unwrap_or(0);
                    let ip = v.get("ip").and_then(|x| x.as_str()).unwrap_or("");
                    let url = v.get("url").and_then(|x| x.as_str()).unwrap_or("");
                    let ua = v.get("ua").and_then(|x| x.as_str()).unwrap_or("");
                    println!("  #{}  {}  {}  UA=\"{}\"", ts, ip, url, ua);
                }
            }
        }
        Err(_) => println!("Обращений пока нет."),
    }
}

fn control_loop(
    rx: mpsc::Receiver<ApprovalReq>,
    approved: Arc<Mutex<Vec<Value>>>,
    allowed: PathBuf,
    knocks: PathBuf,
) {
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
                while line_rx.try_recv().is_ok() {}
                println!();
                println!("=== Запрос безопасной привязки ===");
                println!("Устройство: \"{}\"  (IP {}, id {})", req.name, req.ip, req.id);
                print!("Разрешить/обновить ключ? [y/n] (30 сек): ");
                let _ = io::stdout().flush();
                match line_rx.recv_timeout(Duration::from_secs(30)) {
                    Ok(line) => {
                        let t = line.trim().to_lowercase();
                        if t == "y" || t == "yes" || t == "да" || t == "д" {
                            approve(&approved, &allowed, &req.id, &req.name, &req.key);
                            println!("Привязано: {}", req.name);
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
                    handle_command(&cmd, &approved, &allowed, &knocks);
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
    let mut config = parse_config();
    config.root = match fs::canonicalize(&config.root) {
        Ok(root) => root,
        Err(e) => {
            eprintln!("Корневая папка недоступна ({}): {}", config.root.display(), e);
            std::process::exit(1);
        }
    };

    let allowed_path = config.data_dir.join("allowed.json");
    let knocks_path = config.data_dir.join("access.log");
    let approved = Arc::new(Mutex::new(load_allowed(&allowed_path)));

    let addr = format!("{}:{}", config.host, config.port);
    let server = match Server::http(addr.as_str()) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Не удалось запустить сервер на {}: {}", addr, e);
            std::process::exit(1);
        }
    };

    println!("media-server: {{http://{}}}", addr);
    println!("Корневая папка: {}", config.root.display());
    println!("Имя сервера: {}", config.name);
    println!("Маскировка для чужих запросов: {}", SERVER_BANNER);
    println!("Папка данных (allowed.json / access.log): {}", config.data_dir.display());
    println!("Разрешённых устройств: {}", approved.lock().unwrap().len());

    spawn_discovery(config.name.clone(), config.port);

    let approval_tx: Option<Sender<ApprovalReq>> = if config.background {
        println!("Фоновый режим: пускаются только устройства с ключом из allowed.json.");
        None
    } else {
        let (tx, rx) = mpsc::channel::<ApprovalReq>();
        let approved_c = Arc::clone(&approved);
        let allowed_c = allowed_path.clone();
        let knocks_c = knocks_path.clone();
        thread::spawn(move || control_loop(rx, approved_c, allowed_c, knocks_c));
        println!("Подтверждайте безопасную привязку в этой консоли (y/n). Команды: list, revoke <id|имя>, stats.");
        Some(tx)
    };

    let state = Arc::new(State {
        config,
        knocks_path: knocks_path.clone(),
        nonces: Mutex::new(HashMap::new()),
        downloads: Arc::new(AtomicUsize::new(0)),
        pairings: Arc::new(AtomicUsize::new(0)),
        searches: Arc::new(AtomicUsize::new(0)),
    });
    let active = Arc::new(AtomicUsize::new(0));

    loop {
        let request = match server.recv() {
            Ok(request) => request,
            Err(_) => break,
        };
        let slot = match acquire_slot(&active, MAX_ACTIVE_REQUESTS) {
            Some(slot) => slot,
            None => {
                let _ = respond_html(request, 503, NGINX_503);
                continue;
            }
        };
        let state_w = Arc::clone(&state);
        let approved_w = Arc::clone(&approved);
        let atx = approval_tx.clone();
        thread::spawn(move || {
            let _slot = slot;
            handle(request, &state_w, &approved_w, &atx);
        });
    }
}

fn header_val(request: &Request, name: &str) -> String {
    for h in request.headers() {
        if format!("{}", h.field).eq_ignore_ascii_case(name) {
            return h.value.as_str().to_string();
        }
    }
    String::new()
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn hex_decode(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(s.len() / 2);
    let bytes = s.as_bytes();
    for i in (0..bytes.len()).step_by(2) {
        let hi = (bytes[i] as char).to_digit(16)?;
        let lo = (bytes[i + 1] as char).to_digit(16)?;
        out.push(((hi << 4) | lo) as u8);
    }
    Some(out)
}

fn valid_enrollment_key(key: &str) -> bool {
    key.len() == 64 && key.bytes().all(|b| b.is_ascii_hexdigit())
}

fn verify_proof(key: &str, time: &str, nonce: &str, target: &str, proof: &str) -> bool {
    let ts: u64 = match time.parse() {
        Ok(v) => v,
        Err(_) => return false,
    };
    let now = unix_now();
    if now.abs_diff(ts) > AUTH_WINDOW_SECS {
        return false;
    }
    if nonce.len() < 16 || nonce.len() > 128 || !nonce.bytes().all(|b| b.is_ascii_hexdigit()) {
        return false;
    }
    let signature = match hex_decode(proof) {
        Some(v) if v.len() == 32 => v,
        _ => return false,
    };
    let mut mac = match HmacSha256::new_from_slice(key.as_bytes()) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let message = format!("{}\n{}\n{}", time, nonce, target);
    mac.update(message.as_bytes());
    mac.verify_slice(&signature).is_ok()
}

fn verify_stream_proof(key: &str, time: &str, target: &str, proof: &str) -> bool {
    let ts: u64 = match time.parse() {
        Ok(v) => v,
        Err(_) => return false,
    };
    if unix_now().abs_diff(ts) > STREAM_AUTH_WINDOW_SECS {
        return false;
    }
    let signature = match hex_decode(proof) {
        Some(v) if v.len() == 32 => v,
        _ => return false,
    };
    let mut mac = match HmacSha256::new_from_slice(key.as_bytes()) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let message = format!("stream\n{}\n{}", time, target);
    mac.update(message.as_bytes());
    mac.verify_slice(&signature).is_ok()
}

fn remember_nonce(state: &State, id: &str, nonce: &str, ts: u64) -> bool {
    let now = unix_now();
    let mut all = state.nonces.lock().unwrap();
    let q = all.entry(id.to_string()).or_default();
    while let Some((_, old_ts)) = q.front() {
        if now.saturating_sub(*old_ts) > AUTH_WINDOW_SECS {
            q.pop_front();
        } else {
            break;
        }
    }
    if q.iter().any(|(n, _)| n == nonce) {
        return false;
    }
    q.push_back((nonce.to_string(), ts));
    while q.len() > 128 {
        q.pop_front();
    }
    true
}

fn request_pairing(
    state: &State,
    approval_tx: &Option<Sender<ApprovalReq>>,
    fields: &AuthFields,
    ip: &str,
) -> bool {
    if !valid_enrollment_key(&fields.key) {
        return false;
    }
    let _slot = match acquire_slot(&state.pairings, MAX_PENDING_PAIRINGS) {
        Some(slot) => slot,
        None => return false,
    };
    match approval_tx {
        None => false,
        Some(tx) => {
            let (rtx, rrx) = mpsc::channel();
            let req = ApprovalReq {
                id: fields.id.clone(),
                name: fields.name.clone(),
                ip: ip.to_string(),
                key: fields.key.clone(),
                resp: rtx,
            };
            if tx.send(req).is_err() {
                return false;
            }
            rrx.recv_timeout(Duration::from_secs(35)).unwrap_or(false)
        }
    }
}

fn authorize(
    state: &State,
    approved: &Arc<Mutex<Vec<Value>>>,
    approval_tx: &Option<Sender<ApprovalReq>>,
    fields: &AuthFields,
    ip: &str,
    target: &str,
) -> bool {
    if fields.id.is_empty() || fields.proof.is_empty() {
        return false;
    }

    if fields.stream {
        if let Some(key) = approved_key(approved, &fields.id) {
            if verify_stream_proof(&key, &fields.time, target, &fields.proof) {
                return true;
            }
            if verify_stream_proof(&fields.key, &fields.time, target, &fields.proof) {
                return request_pairing(state, approval_tx, fields, ip);
            }
            return false;
        }
        return verify_stream_proof(&fields.key, &fields.time, target, &fields.proof)
            && request_pairing(state, approval_tx, fields, ip);
    }

    if fields.time.is_empty() || fields.nonce.is_empty() {
        return false;
    }
    if let Some(key) = approved_key(approved, &fields.id) {
        if verify_proof(&key, &fields.time, &fields.nonce, target, &fields.proof) {
            let ts = fields.time.parse().unwrap_or(0);
            return remember_nonce(state, &fields.id, &fields.nonce, ts);
        }
        // Установка приложения могла создать новый секрет. Перепривязка всегда требует y в консоли.
        if verify_proof(&fields.key, &fields.time, &fields.nonce, target, &fields.proof)
            && request_pairing(state, approval_tx, fields, ip)
        {
            let ts = fields.time.parse().unwrap_or(0);
            return remember_nonce(state, &fields.id, &fields.nonce, ts);
        }
        return false;
    }

    // Новое устройство или старая запись allowed.json без ключа.
    if !verify_proof(&fields.key, &fields.time, &fields.nonce, target, &fields.proof) {
        return false;
    }
    if !request_pairing(state, approval_tx, fields, ip) {
        return false;
    }
    let ts = fields.time.parse().unwrap_or(0);
    remember_nonce(state, &fields.id, &fields.nonce, ts)
}

fn log_knock(path: &Path, ip: &str, method: &str, url: &str, ua: &str) {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(meta) = fs::metadata(path) {
        if meta.len() > 1_000_000 {
            let _ = fs::write(path, "");
        }
    }
    let line = json!({ "ts": unix_now(), "ip": ip, "method": method, "url": url, "ua": ua }).to_string();
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "{}", line);
    }
}

fn unauthenticated_target(path: &str, query: &str) -> String {
    let kept: Vec<&str> = query
        .split('&')
        .filter(|pair| {
            let key = pair.splitn(2, '=').next().unwrap_or("");
            !matches!(key, "dev" | "dn" | "ts" | "nonce" | "sig" | "key" | "mode")
        })
        .filter(|pair| !pair.is_empty())
        .collect();
    if kept.is_empty() {
        path.to_string()
    } else {
        format!("{}?{}", path, kept.join("&"))
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
    let safe_url = unauthenticated_target(&path_part, &query_part);

    let ip = request.remote_addr().map(|a| a.ip().to_string()).unwrap_or_default();
    let ua = header_val(&request, "User-Agent");
    let is_api = matches!(path_part.as_str(), "/list" | "/search" | "/download");

    let mut fields = AuthFields {
        id: header_val(&request, "X-Device-Id"),
        name: header_val(&request, "X-Device-Name"),
        key: header_val(&request, "X-Device-Key"),
        time: header_val(&request, "X-Auth-Time"),
        nonce: header_val(&request, "X-Auth-Nonce"),
        proof: header_val(&request, "X-Auth-Proof"),
        stream: false,
    };
    if fields.id.is_empty() {
        fields.id = query_get(&query_part, "dev").unwrap_or_default();
    }
    if fields.name.is_empty() {
        fields.name = query_get(&query_part, "dn").unwrap_or_default();
    }
    if fields.key.is_empty() {
        fields.key = query_get(&query_part, "key").unwrap_or_default();
    }
    if fields.time.is_empty() {
        fields.time = query_get(&query_part, "ts").unwrap_or_default();
    }
    if fields.nonce.is_empty() {
        fields.nonce = query_get(&query_part, "nonce").unwrap_or_default();
    }
    if fields.proof.is_empty() {
        fields.proof = query_get(&query_part, "sig").unwrap_or_default();
    }
    fields.stream = query_get(&query_part, "mode").as_deref() == Some("stream");
    if fields.name.is_empty() {
        fields.name = "неизвестное устройство".to_string();
    }

    let has_protocol = !fields.id.is_empty()
        && !fields.proof.is_empty()
        && !fields.time.is_empty()
        && (fields.stream || !fields.nonce.is_empty());
    if !is_api || !has_protocol {
        log_knock(&state.knocks_path, &ip, &method, &safe_url, &ua);
        let s = respond_decoy(request, &path_part);
        println!("{} {} -> {} (обманка)", method, safe_url, s);
        return;
    }

    if !authorize(state, approved, approval_tx, &fields, &ip, &safe_url) {
        log_knock(&state.knocks_path, &ip, &method, &safe_url, &ua);
        let s = respond_html(request, 403, NGINX_403);
        println!("{} {} -> {} ({})", method, safe_url, s, fields.name);
        return;
    }

    let s = if path_part == "/list" {
        let rel = query_get(&query_part, "path").unwrap_or_default();
        respond_list(request, &state.config, &rel)
    } else if path_part == "/search" {
        let q = query_get(&query_part, "q").unwrap_or_default();
        let rel = query_get(&query_part, "path").unwrap_or_default();
        respond_search(request, state, &q, &rel)
    } else {
        let rel = query_get(&query_part, "path").unwrap_or_default();
        respond_download(request, state, &rel)
    };
    println!("{} {} -> {}", method, safe_url, s);
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

fn resolve_existing(root: &Path, rel: &str) -> io::Result<PathBuf> {
    let mut candidate = root.to_path_buf();
    for comp in Path::new(rel).components() {
        match comp {
            Component::Normal(part) => candidate.push(part),
            Component::CurDir => {}
            _ => return Err(io::Error::new(io::ErrorKind::PermissionDenied, "bad path")),
        }
    }
    let canonical = fs::canonicalize(candidate)?;
    if canonical.starts_with(root) {
        Ok(canonical)
    } else {
        Err(io::Error::new(io::ErrorKind::PermissionDenied, "outside root"))
    }
}

fn server_header() -> Header {
    Header::from_bytes(&b"Server"[..], SERVER_BANNER.as_bytes()).unwrap()
}

fn respond_text(request: Request, code: u16, body: &str) -> u16 {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"text/plain; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(body)
        .with_status_code(StatusCode(code))
        .with_header(header)
        .with_header(server_header());
    let _ = request.respond(response);
    code
}

fn respond_html(request: Request, code: u16, body: &str) -> u16 {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"text/html; charset=UTF-8"[..]).unwrap();
    let response = Response::from_string(body)
        .with_status_code(StatusCode(code))
        .with_header(header)
        .with_header(server_header());
    let _ = request.respond(response);
    code
}

fn respond_decoy(request: Request, path: &str) -> u16 {
    if path == "/" || path == "/index.html" {
        respond_html(request, 200, NGINX_WELCOME)
    } else {
        respond_html(request, 404, NGINX_404)
    }
}

fn respond_json(request: Request, code: u16, value: Value) -> u16 {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..]).unwrap();
    let response = Response::from_string(value.to_string())
        .with_status_code(StatusCode(code))
        .with_header(header)
        .with_header(server_header());
    let _ = request.respond(response);
    code
}

fn path_error(request: Request, err: io::Error) -> u16 {
    if err.kind() == io::ErrorKind::PermissionDenied {
        respond_text(request, 403, "forbidden path")
    } else {
        respond_text(request, 404, "not found")
    }
}

fn respond_list(request: Request, config: &Config, rel: &str) -> u16 {
    let dir = match resolve_existing(&config.root, rel) {
        Ok(p) => p,
        Err(e) => return path_error(request, e),
    };
    let read = match fs::read_dir(&dir) {
        Ok(r) => r,
        Err(_) => return respond_text(request, 404, "not found"),
    };
    let mut list: Vec<(String, bool, u64)> = Vec::new();
    let mut truncated = false;
    for item in read {
        if list.len() >= MAX_LIST_ENTRIES {
            truncated = true;
            break;
        }
        let entry = match item {
            Ok(entry) => entry,
            Err(_) => continue,
        };
        let file_type = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        if file_type.is_symlink() {
            continue;
        }
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let name = entry.file_name().to_string_lossy().to_string();
        let is_dir = meta.is_dir();
        let size = if is_dir { 0 } else { meta.len() };
        list.push((name, is_dir, size));
    }
    list.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    let entries: Vec<Value> = list
        .iter()
        .map(|(name, is_dir, size)| json!({ "name": name, "is_dir": is_dir, "size": size }))
        .collect();
    respond_json(
        request,
        200,
        json!({ "path": rel, "abs_path": dir.display().to_string(), "truncated": truncated, "entries": entries }),
    )
}

fn respond_search(request: Request, state: &State, query: &str, rel: &str) -> u16 {
    let _slot = match acquire_slot(&state.searches, MAX_SEARCHES) {
        Some(slot) => slot,
        None => return respond_html(request, 503, NGINX_503),
    };
    let config = &state.config;
    let query = query.trim();
    if query.is_empty() || query.chars().count() > 200 {
        return respond_text(request, 400, "bad query");
    }
    let base = match resolve_existing(&config.root, rel) {
        Ok(p) => p,
        Err(e) => return path_error(request, e),
    };
    if !base.is_dir() {
        return respond_text(request, 400, "not a directory");
    }

    let needle = query.to_lowercase();
    let started = Instant::now();
    let mut visited = 0usize;
    let mut truncated = false;
    let mut results: Vec<(String, bool, u64, String)> = Vec::new();
    let mut stack: Vec<(PathBuf, usize)> = vec![(base, 0)];

    'walk: while let Some((current, depth)) = stack.pop() {
        if results.len() >= SEARCH_RESULT_LIMIT
            || visited >= SEARCH_VISIT_LIMIT
            || started.elapsed() >= SEARCH_TIME_LIMIT
        {
            truncated = true;
            break;
        }
        let read = match fs::read_dir(&current) {
            Ok(r) => r,
            Err(_) => continue,
        };
        for item in read {
            if results.len() >= SEARCH_RESULT_LIMIT
                || visited >= SEARCH_VISIT_LIMIT
                || started.elapsed() >= SEARCH_TIME_LIMIT
            {
                truncated = true;
                break 'walk;
            }
            visited += 1;
            let entry = match item {
                Ok(e) => e,
                Err(_) => continue,
            };
            let file_type = match entry.file_type() {
                Ok(t) => t,
                Err(_) => continue,
            };
            if file_type.is_symlink() {
                continue;
            }
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            let name = entry.file_name().to_string_lossy().to_string();
            let full = entry.path();
            let is_dir = meta.is_dir();
            if is_dir {
                if depth < SEARCH_DEPTH_LIMIT {
                    stack.push((full.clone(), depth + 1));
                } else {
                    truncated = true;
                }
            }
            if name.to_lowercase().contains(&needle) {
                let relpath = full
                    .strip_prefix(&config.root)
                    .unwrap_or(&full)
                    .to_string_lossy()
                    .to_string();
                let size = if is_dir { 0 } else { meta.len() };
                results.push((name, is_dir, size, relpath));
            }
        }
    }
    results.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    let entries: Vec<Value> = results
        .iter()
        .map(|(name, is_dir, size, path)| json!({ "name": name, "is_dir": is_dir, "size": size, "path": path }))
        .collect();
    respond_json(
        request,
        200,
        json!({ "query": query, "count": entries.len(), "visited": visited, "truncated": truncated, "entries": entries }),
    )
}

fn parse_range(range: &str, total: u64) -> Option<(u64, u64)> {
    let range = range.trim();
    if !range.starts_with("bytes=") || total == 0 {
        return None;
    }
    let spec = &range[6..];
    if spec.contains(',') {
        return None;
    }
    let mut parts = spec.splitn(2, '-');
    let start_s = parts.next().unwrap_or("");
    let end_s = parts.next().unwrap_or("");
    if start_s.is_empty() {
        let suffix: u64 = end_s.parse().ok()?;
        if suffix == 0 {
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

fn respond_range_error(request: Request, total: u64) -> u16 {
    let value = format!("bytes */{}", total);
    let cr = Header::from_bytes(&b"Content-Range"[..], value.as_bytes()).unwrap();
    let response = Response::from_string("")
        .with_status_code(StatusCode(416))
        .with_header(cr)
        .with_header(server_header());
    let _ = request.respond(response);
    416
}

fn respond_download(request: Request, state: &State, rel: &str) -> u16 {
    let _slot = match acquire_slot(&state.downloads, MAX_DOWNLOADS) {
        Some(slot) => slot,
        None => return respond_html(request, 503, NGINX_503),
    };
    let path = match resolve_existing(&state.config.root, rel) {
        Ok(p) => p,
        Err(e) => return path_error(request, e),
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
        let (start, end) = match parse_range(&rh, total) {
            Some(v) => v,
            None => return respond_range_error(request, total),
        };
        let length = end - start + 1;
        if file.seek(SeekFrom::Start(start)).is_err() {
            return respond_text(request, 500, "seek error");
        }
        let reader = file.take(length);
        let cr_value = format!("bytes {}-{}/{}", start, end, total);
        let cr = Header::from_bytes(&b"Content-Range"[..], cr_value.as_bytes()).unwrap();
        let response = Response::new(
            StatusCode(206),
            vec![ct, cd, ar, cr, server_header()],
            reader,
            Some(length as usize),
            None,
        );
        let _ = request.respond(response);
        return 206;
    }

    let response = Response::new(
        StatusCode(200),
        vec![ct, cd, ar, server_header()],
        file,
        Some(total as usize),
        None,
    );
    let _ = request.respond(response);
    200
}
