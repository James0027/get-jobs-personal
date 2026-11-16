CREATE TABLE boss_config (
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
CREATE TABLE boss_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT,
    name TEXT,
    code TEXT,
    sort_order INTEGER,
    created_at TEXT,
    updated_at TEXT
);
CREATE TABLE boss_blacklist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT,
    value TEXT,
    created_at TEXT,
    updated_at TEXT
);
CREATE TABLE liepin_config (id INTEGER PRIMARY KEY AUTOINCREMENT, keywords TEXT, city TEXT, salary_code TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE job51_config (id INTEGER PRIMARY KEY AUTOINCREMENT, keywords TEXT, job_area TEXT, salary TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE zhilian_config (id INTEGER PRIMARY KEY AUTOINCREMENT, keywords TEXT, city_code TEXT, salary TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE config (id INTEGER PRIMARY KEY AUTOINCREMENT, config_key TEXT, config_value TEXT, config_type TEXT, category TEXT, description TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE ai (id INTEGER PRIMARY KEY AUTOINCREMENT, introduce TEXT, prompt TEXT, created_at DATETIME, updated_at DATETIME);
CREATE TABLE cookie (id INTEGER PRIMARY KEY AUTOINCREMENT, platform VARCHAR(50) NOT NULL, cookie_value TEXT NOT NULL, remark TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE liepin_option (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 type TEXT,
 name TEXT,
 code TEXT,
 sort_order INTEGER,
 created_at DATETIME,
 updated_at DATETIME
);
CREATE TABLE job51_option (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 type TEXT,
 name TEXT,
 code TEXT,
 sort_order INTEGER,
 created_at DATETIME,
 updated_at DATETIME
);
CREATE TABLE zhilian_option (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 type TEXT,
 name TEXT,
 code TEXT,
 sort_order INTEGER,
 created_at DATETIME,
 updated_at DATETIME
);
CREATE TABLE boss_chat_profile (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL);
CREATE TABLE boss_chat_inbound (conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, payload TEXT NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY(conversation_id,message_id));
CREATE TABLE boss_chat_suggestion (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(conversation_id,message_id));
CREATE TABLE boss_chat_followup (conversation_id TEXT PRIMARY KEY, count INTEGER NOT NULL, last_sent_at INTEGER NOT NULL);
CREATE TABLE liepin_data ( job_id            BIGINT PRIMARY KEY, job_title         VARCHAR(200), job_link          VARCHAR(300), job_salary_text   VARCHAR(100), job_area          VARCHAR(100), job_edu_req       VARCHAR(50), job_exp_req       VARCHAR(50), job_publish_time  VARCHAR(50), comp_id           BIGINT, comp_name         VARCHAR(200), comp_industry     VARCHAR(100), comp_scale        VARCHAR(50), hr_id             VARCHAR(64), hr_name           VARCHAR(50), hr_title          VARCHAR(100), hr_im_id          VARCHAR(64), delivered         INTEGER DEFAULT 0, create_time       DATETIME, update_time       DATETIME);
CREATE TABLE zhilian_data ( id INTEGER PRIMARY KEY AUTOINCREMENT, job_id VARCHAR(64), job_title VARCHAR(200), job_link VARCHAR(300), salary VARCHAR(100), location VARCHAR(100), experience VARCHAR(100), degree VARCHAR(100), company_name VARCHAR(200), delivery_status VARCHAR(20) DEFAULT '未投递', create_time DATETIME, update_time DATETIME);
CREATE TABLE job51_data ( job_id            BIGINT PRIMARY KEY, job_title         VARCHAR(200), job_link          VARCHAR(300), job_salary_text   VARCHAR(100), job_area          VARCHAR(100), job_edu_req       VARCHAR(50), job_exp_req       VARCHAR(50), job_publish_time  VARCHAR(50), comp_id           BIGINT, comp_name         VARCHAR(200), comp_industry     VARCHAR(100), comp_scale        VARCHAR(50), hr_id             VARCHAR(64), hr_name           VARCHAR(50), hr_title          VARCHAR(100), delivered         INTEGER DEFAULT 0, create_time       TEXT, update_time       TEXT);
CREATE UNIQUE INDEX idx_cookie_platform ON cookie(platform);
CREATE TABLE search_template ( id TEXT PRIMARY KEY, name TEXT NOT NULL, is_default INTEGER NOT NULL CHECK(is_default IN (0,1)), version INTEGER NOT NULL CHECK(version>0), criteria_json TEXT NOT NULL, say_hi_draft TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, expected_salary TEXT, arrival_intention TEXT, linked_resume_asset_id TEXT, personal_intro TEXT, communication_style TEXT );
CREATE UNIQUE INDEX search_template_one_default ON search_template(is_default) WHERE is_default=1;
CREATE TABLE search_template_override ( template_id TEXT NOT NULL REFERENCES search_template(id) ON DELETE CASCADE, platform_id TEXT NOT NULL CHECK(platform_id IN ('BOSS','LIEPIN','JOB51','ZHILIAN')), version INTEGER NOT NULL CHECK(version>0), fields_json TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(template_id,platform_id) );
CREATE TABLE default_page_config_seed (id INTEGER PRIMARY KEY);
CREATE TABLE boss_delivery_task (task_id TEXT PRIMARY KEY, snapshot TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE boss_delivery_reconciliation (id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,job_id TEXT NOT NULL,version INTEGER NOT NULL,confirmed INTEGER NOT NULL,created_at TEXT NOT NULL);
