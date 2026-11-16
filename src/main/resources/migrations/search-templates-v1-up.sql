CREATE TABLE IF NOT EXISTS search_template (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, is_default INTEGER NOT NULL CHECK(is_default IN (0,1)),
 version INTEGER NOT NULL CHECK(version>0), criteria_json TEXT NOT NULL, say_hi_draft TEXT NOT NULL,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 expected_salary TEXT, arrival_intention TEXT, linked_resume_asset_id TEXT,
 personal_intro TEXT, communication_style TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS search_template_one_default ON search_template(is_default) WHERE is_default=1;
CREATE TABLE IF NOT EXISTS search_template_override (
 template_id TEXT NOT NULL REFERENCES search_template(id) ON DELETE CASCADE,
 platform_id TEXT NOT NULL CHECK(platform_id IN ('BOSS','LIEPIN','JOB51','ZHILIAN')),
 version INTEGER NOT NULL CHECK(version>0), fields_json TEXT NOT NULL, updated_at TEXT NOT NULL,
 PRIMARY KEY(template_id,platform_id)
);
