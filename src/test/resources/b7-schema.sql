-- BOSS 相关表 DDL（推导自 BossConfigEntity, BossOptionEntity, BlacklistEntity）
CREATE TABLE IF NOT EXISTS boss_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    debugger INTEGER,
    wait_time INTEGER,
    keywords TEXT,
    city_code TEXT,
    target_count INTEGER,
    graduation_year INTEGER,
    ai_only INTEGER,
    accepted_cities TEXT,
    approved_resume INTEGER,
    auto_resume INTEGER,
    auto_location INTEGER,
    enable_follow_up INTEGER,
    industry TEXT,
    job_type TEXT,
    experience TEXT,
    degree TEXT,
    salary TEXT,
    scale TEXT,
    stage TEXT,
    say_hi TEXT,
    expected_salary_min INTEGER,
    expected_salary_max INTEGER,
    enable_ai INTEGER,
    send_img_resume INTEGER,
    allow_repeat_chat INTEGER,
    repeat_chat_messages TEXT,
    filter_dead_hr INTEGER,
    dead_status TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS boss_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT,
    name TEXT,
    code TEXT,
    sort_order INTEGER,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS boss_blacklist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT,
    value TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS liepin_config (id INTEGER PRIMARY KEY AUTOINCREMENT, keywords TEXT, city TEXT, salary_code TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE IF NOT EXISTS job51_config (id INTEGER PRIMARY KEY AUTOINCREMENT, keywords TEXT, job_area TEXT, salary TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE IF NOT EXISTS zhilian_config (id INTEGER PRIMARY KEY AUTOINCREMENT, keywords TEXT, city_code TEXT, salary TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE IF NOT EXISTS config (id INTEGER PRIMARY KEY AUTOINCREMENT, config_key TEXT, config_value TEXT, config_type TEXT, category TEXT, description TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE IF NOT EXISTS ai (id INTEGER PRIMARY KEY AUTOINCREMENT, introduce TEXT, prompt TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE IF NOT EXISTS cookie (id INTEGER PRIMARY KEY AUTOINCREMENT, platform VARCHAR(50) NOT NULL, cookie_value TEXT NOT NULL, remark TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP);
