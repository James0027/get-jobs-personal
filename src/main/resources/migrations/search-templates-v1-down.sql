-- Stop the new endpoints and export templates before rollback. Existing tables are untouched.
DROP TABLE IF EXISTS search_template_override;
DROP TABLE IF EXISTS search_template;
