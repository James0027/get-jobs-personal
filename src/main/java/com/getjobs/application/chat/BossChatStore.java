package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.getjobs.application.chat.ChatContracts.*;

@Repository
public class BossChatStore {
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BossChatStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS boss_chat_profile (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS boss_chat_inbound (conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, payload TEXT NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY(conversation_id,message_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS boss_chat_suggestion (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(conversation_id,message_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS boss_chat_followup (conversation_id TEXT PRIMARY KEY, count INTEGER NOT NULL, last_sent_at INTEGER NOT NULL)");
        // A process crash after an external send cannot safely be treated as a failure/retried.
        jdbc.update("UPDATE boss_chat_suggestion SET state='unknown' WHERE state='sending'");
    }

    public synchronized Profile profile() {
        var rows = jdbc.queryForList("SELECT payload FROM boss_chat_profile WHERE id=1", String.class);
        return rows.isEmpty() ? Profile.empty() : read(rows.getFirst(), Profile.class);
    }

    public synchronized Profile saveProfile(Profile p) {
        if (p == null) throw new IllegalArgumentException("个人资料不能为空");
        if (p.graduationYear() != null && (p.graduationYear() < 2000 || p.graduationYear() > 2100))
            throw new IllegalArgumentException("毕业年份应在2000到2100之间");
        if (p.resumeText() != null && p.resumeText().codePointCount(0, p.resumeText().length()) > 20000)
            throw new IllegalArgumentException("简历文本不能超过20000字符");

        Profile old = profile();
        String newText = p.resumeText() == null ? "" : p.resumeText();
        String oldText = old.resumeText() == null ? "" : old.resumeText();
        String approvedHash = newText.equals(oldText) ? (old.approvedResumeTextHash() == null ? "" : old.approvedResumeTextHash()) : "";

        return doSave(old, p, approvedHash);
    }

    public synchronized Profile approveResumeText(String expectedProfileVersion, String expectedTextHash) {
        Profile current = profile();
        if (current.resumeTextHash().isEmpty() || expectedTextHash == null || expectedTextHash.isEmpty()
                || !current.version().equals(expectedProfileVersion) || !current.resumeTextHash().equals(expectedTextHash)) {
            throw new IllegalStateException("资料已变化，请刷新后重新批准");
        }
        return doSave(current, current, current.resumeTextHash());
    }

    private Profile doSave(Profile old, Profile p, String approvedHash) {
        validateResume(p.resume());
        validateFollowUp(p.followUp());

        Set<String> previousDerived = new HashSet<>(old.derivedFactIds() == null ? List.of() : old.derivedFactIds());
        List<Fact> requestFacts = p.facts() == null ? List.of() : p.facts();
        List<Fact> legacyFacts = requestFacts.stream()
                .filter(f -> f != null && !previousDerived.contains(f.id()))
                .toList();

        Set<String> ids = new HashSet<>();
        for (Fact f : legacyFacts) {
            if (f.id() == null || f.id().isBlank() || f.id().startsWith("location:") || f.id().equals("graduationYear")
                    || !ids.add(f.id()) || f.text() == null || f.text().isBlank() || f.text().length() > 2000)
                throw new IllegalArgumentException("个人事实必须有唯一ID和有效文本");
        }

        Resume resume = p.resume() == null ? Resume.empty() : p.resume();
        String resumeText = p.resumeText() == null ? "" : p.resumeText();
        List<Fact> derived = derivedFacts(resume, approvedHash, resumeText);

        for (Fact df : derived) {
            if (ids.contains(df.id())) {
                throw new IllegalArgumentException("资料条目ID冲突，请重新提交该条目");
            }
        }

        List<Fact> allFacts = new ArrayList<>(legacyFacts);
        allFacts.addAll(derived);
        if (allFacts.size() > 400) {
            throw new IllegalArgumentException("个人事实总数不能超过400条");
        }

        List<String> derivedFactIds = derived.stream().map(Fact::id).toList();
        var cities = p.acceptedCities() == null ? List.<String>of() : p.acceptedCities().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        String asset = p.approvedResumeAssetId() == null ? "" : p.approvedResumeAssetId().trim();
        String pdfAsset = p.approvedPdfAssetId() == null ? "" : p.approvedPdfAssetId().trim();
        String resumeTextHash = sha256(resumeText);
        FollowUp followUp = p.followUp() == null ? FollowUp.defaults() : p.followUp();

        Profile saved = new Profile(
                UUID.randomUUID().toString(),
                p.graduationYear(),
                cities,
                allFacts,
                asset,
                p.autoResume(),
                p.autoLocation(),
                p.enabled(),
                resume,
                pdfAsset,
                resumeText,
                resumeTextHash,
                approvedHash,
                derivedFactIds,
                p.autoReply(),
                followUp
        );

        jdbc.update("INSERT INTO boss_chat_profile(id,payload) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload", json(saved));
        jdbc.update("UPDATE boss_chat_suggestion SET state='stale' WHERE state='pending_approval'");
        return saved;
    }

    private void validateResume(Resume resume) {
        if (resume == null) return;
        if (resume.work().size() > 30) throw new IllegalArgumentException("工作经历条目不能超过30条");
        Set<String> workIds = new HashSet<>();
        for (WorkItem w : resume.work()) {
            if (w == null || w.id() == null || !ID_PATTERN.matcher(w.id()).matches() || !workIds.add(w.id()))
                throw new IllegalArgumentException("工作经历ID格式不正确或重复");
            if (w.company() == null || w.company().isBlank() || w.company().length() > 100)
                throw new IllegalArgumentException("公司名称不能为空且不超过100字符");
            if (w.title() == null || w.title().isBlank() || w.title().length() > 100)
                throw new IllegalArgumentException("职位名称不能为空且不超过100字符");
            if (w.period() != null && w.period().length() > 40)
                throw new IllegalArgumentException("工作时间不能超过40字符");
            if (w.summary() != null && w.summary().length() > 1000)
                throw new IllegalArgumentException("工作描述不能超过1000字符");
        }

        if (resume.project().size() > 30) throw new IllegalArgumentException("项目经历条目不能超过30条");
        Set<String> projectIds = new HashSet<>();
        for (ProjectItem p : resume.project()) {
            if (p == null || p.id() == null || !ID_PATTERN.matcher(p.id()).matches() || !projectIds.add(p.id()))
                throw new IllegalArgumentException("项目经历ID格式不正确或重复");
            if (p.name() == null || p.name().isBlank() || p.name().length() > 100)
                throw new IllegalArgumentException("项目名称不能为空且不超过100字符");
            if (p.role() != null && p.role().length() > 60)
                throw new IllegalArgumentException("项目角色不能超过60字符");
            if (p.period() != null && p.period().length() > 40)
                throw new IllegalArgumentException("项目时间不能超过40字符");
            if (p.description() != null && p.description().length() > 1000)
                throw new IllegalArgumentException("项目描述不能超过1000字符");
        }

        if (resume.education().size() > 30) throw new IllegalArgumentException("教育经历条目不能超过30条");
        Set<String> eduIds = new HashSet<>();
        for (EducationItem e : resume.education()) {
            if (e == null || e.id() == null || !ID_PATTERN.matcher(e.id()).matches() || !eduIds.add(e.id()))
                throw new IllegalArgumentException("教育经历ID格式不正确或重复");
            if (e.school() == null || e.school().isBlank() || e.school().length() > 100)
                throw new IllegalArgumentException("学校名称不能为空且不超过100字符");
            if (e.degree() != null && e.degree().length() > 60)
                throw new IllegalArgumentException("学历不能超过60字符");
            if (e.major() != null && e.major().length() > 60)
                throw new IllegalArgumentException("专业不能超过60字符");
            if (e.period() != null && e.period().length() > 40)
                throw new IllegalArgumentException("教育时间不能超过40字符");
        }

        if (resume.skills().size() > 30) throw new IllegalArgumentException("技能条目不能超过30条");
        Set<String> skillIds = new HashSet<>();
        for (SkillItem s : resume.skills()) {
            if (s == null || s.id() == null || !ID_PATTERN.matcher(s.id()).matches() || !skillIds.add(s.id()))
                throw new IllegalArgumentException("技能ID格式不正确或重复");
            if (s.name() == null || s.name().isBlank() || s.name().length() > 100)
                throw new IllegalArgumentException("技能名称不能为空且不超过100字符");
            if (s.level() != null && s.level().length() > 20)
                throw new IllegalArgumentException("技能熟练度不能超过20字符");
            if (s.description() != null && s.description().length() > 1000)
                throw new IllegalArgumentException("技能描述不能超过1000字符");
        }
    }

    private void validateFollowUp(FollowUp f) {
        if (f == null) return;
        if (f.cooldownHours() != null && (f.cooldownHours() < 24 || f.cooldownHours() > 720))
            throw new IllegalArgumentException("跟进冷却时间应在24到720小时之间");
        if (f.maxPerConversation() != null && (f.maxPerConversation() < 1 || f.maxPerConversation() > 5))
            throw new IllegalArgumentException("每对话最大跟进次数应在1到5次之间");
    }

    static List<Fact> derivedFacts(Resume resume, String approvedHash, String text) {
        List<Fact> facts = new ArrayList<>();
        if (resume != null) {
            for (WorkItem w : resume.work()) {
                String joined = Stream.of(w.company(), w.title(), w.period(), w.summary())
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .collect(Collectors.joining("｜"));
                facts.add(new Fact("work:" + w.id(), "工作经历：" + joined));
            }
            for (ProjectItem p : resume.project()) {
                String joined = Stream.of(p.name(), p.role(), p.period(), p.description())
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .collect(Collectors.joining("｜"));
                facts.add(new Fact("project:" + p.id(), "项目经历：" + joined));
            }
            for (EducationItem e : resume.education()) {
                String joined = Stream.of(e.school(), e.degree(), e.major(), e.period())
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .collect(Collectors.joining("｜"));
                facts.add(new Fact("education:" + e.id(), "教育背景：" + joined));
            }
            for (SkillItem skill : resume.skills()) {
                String joined = Stream.of(skill.name(), skill.level(), skill.description())
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .collect(Collectors.joining("｜"));
                facts.add(new Fact("skill:" + skill.id(), "专业技能：" + joined));
            }
        }

        if (approvedHash != null && !approvedHash.isBlank() && text != null && !text.isBlank() && approvedHash.equals(sha256(text))) {
            String hash8 = approvedHash.substring(0, Math.min(8, approvedHash.length()));
            List<String> chunks = chunkResumeText(text);
            int m = chunks.size();
            for (int n = 0; n < m; n++) {
                facts.add(new Fact("resume:" + hash8 + ":" + n, "简历文本第" + (n + 1) + "段/共" + m + "段：" + chunks.get(n)));
            }
        }
        return facts;
    }

    static List<String> chunkResumeText(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.replace("\r\n", "\n");
        String[] paragraphs = normalized.split("\n\n");
        List<String> pieces = new ArrayList<>();
        for (String p : paragraphs) {
            if (p.isEmpty()) continue;
            int cpCount = p.codePointCount(0, p.length());
            if (cpCount <= 1900) {
                pieces.add(p);
            } else {
                int start = 0;
                int len = p.length();
                while (start < len) {
                    int remainingCp = p.codePointCount(start, len - start);
                    int takeCp = Math.min(1900, remainingCp);
                    int end = p.offsetByCodePoints(start, takeCp);
                    pieces.add(p.substring(start, end));
                    start = end;
                }
            }
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (current.isEmpty()) {
                current.append(piece);
            } else {
                int currCp = current.codePointCount(0, current.length());
                int pieceCp = piece.codePointCount(0, piece.length());
                if (currCp + 2 + pieceCp <= 1900) {
                    current.append("\n\n").append(piece);
                } else {
                    chunks.add(current.toString());
                    current.setLength(0);
                    current.append(piece);
                }
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        if (chunks.size() > 11) {
            chunks = chunks.subList(0, 11);
        }
        return chunks;
    }

    static String sha256(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized boolean ingest(Inbound inbound) {
        int changed = jdbc.update("INSERT OR IGNORE INTO boss_chat_inbound(conversation_id,message_id,payload,received_at) VALUES(?,?,?,?)",
                inbound.conversationId(), inbound.lastInboundMessageId(), json(inbound), System.currentTimeMillis());
        if (changed > 0) jdbc.update("UPDATE boss_chat_suggestion SET state='stale' WHERE conversation_id=? AND message_id<>? AND state='pending_approval'", inbound.conversationId(), inbound.lastInboundMessageId());
        return changed > 0;
    }

    public boolean hasConversation(String conversationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM boss_chat_inbound WHERE conversation_id=?", Integer.class, conversationId) > 0;
    }

    public boolean hasMessage(String conversationId, String messageId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM boss_chat_inbound WHERE conversation_id=? AND message_id=?", Integer.class, conversationId, messageId) > 0;
    }

    public synchronized void save(Suggestion s) {
        jdbc.update("INSERT OR IGNORE INTO boss_chat_suggestion(id,conversation_id,message_id,state,payload) VALUES(?,?,?,?,?)", s.id(), s.conversationId(), s.lastInboundMessageId(), s.state(), json(s));
    }

    public synchronized List<Suggestion> suggestions() {
        return jdbc.query("SELECT payload,state FROM boss_chat_suggestion ORDER BY rowid DESC LIMIT 200", (r, n) -> withState(read(r.getString(1), Suggestion.class), r.getString(2)));
    }

    public synchronized List<Suggestion> records(List<String> states, int limit) {
        if (states == null || states.isEmpty()) return List.of();
        int clampedLimit = Math.max(1, Math.min(200, limit));
        String placeholders = String.join(",", Collections.nCopies(states.size(), "?"));
        String sql = "SELECT payload,state FROM boss_chat_suggestion WHERE state IN (" + placeholders + ") ORDER BY rowid DESC LIMIT ?";
        Object[] args = new Object[states.size() + 1];
        for (int i = 0; i < states.size(); i++) args[i] = states.get(i);
        args[states.size()] = clampedLimit;
        return jdbc.query(sql, (r, n) -> {
            Suggestion s = withState(read(r.getString(1), Suggestion.class), r.getString(2));
            String src = s.sourceText();
            if (src != null && src.length() > 200) src = src.substring(0, 200);
            return new Suggestion(s.id(), s.version(), s.conversationId(), s.lastInboundMessageId(), s.profileVersion(), s.state(), s.replyText(), s.intent(), s.action(), s.reasonCode(), s.missingFacts(), src, s.resumeAssetId(), s.evidenceFactIds(), s.createdAt());
        }, args);
    }

    public synchronized String reconcileUnknown(String id, boolean confirmed) {
        String targetState = confirmed ? "sent" : "failed";
        int changed = jdbc.update("UPDATE boss_chat_suggestion SET state=? WHERE id=? AND state='unknown'", targetState, id);
        if (changed == 0) throw new IllegalStateException("记录已变化，请刷新");
        return targetState;
    }

    public synchronized Suggestion get(String id) {
        return jdbc.query("SELECT payload,state FROM boss_chat_suggestion WHERE id=?", (r, n) -> withState(read(r.getString(1), Suggestion.class), r.getString(2)), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("建议不存在"));
    }

    public synchronized boolean transition(String id, String from, String to) {
        return jdbc.update("UPDATE boss_chat_suggestion SET state=? WHERE id=? AND state=?", to, id, from) == 1;
    }

    public synchronized boolean claim(Suggestion s, String approvedText) {
        Suggestion approved = new Suggestion(s.id(), s.version(), s.conversationId(), s.lastInboundMessageId(), s.profileVersion(), "sending", approvedText, s.intent(), s.action(), s.reasonCode(), s.missingFacts(), s.sourceText(), s.resumeAssetId(), s.evidenceFactIds(), s.createdAt());
        return jdbc.update("UPDATE boss_chat_suggestion SET state='sending',payload=? WHERE id=? AND state='pending_approval'", json(approved), s.id()) == 1;
    }

    public synchronized boolean beginAutoSend(String id, String approvedText) {
        var rows = jdbc.queryForList("SELECT payload FROM boss_chat_suggestion WHERE id=? AND state='blocked'", String.class, id);
        if (rows.isEmpty()) return false;
        Suggestion s = read(rows.getFirst(), Suggestion.class);
        Suggestion sending = new Suggestion(s.id(), s.version(), s.conversationId(), s.lastInboundMessageId(), s.profileVersion(), "sending", approvedText, s.intent(), s.action(), s.reasonCode(), s.missingFacts(), s.sourceText(), s.resumeAssetId(), s.evidenceFactIds(), s.createdAt());
        return jdbc.update("UPDATE boss_chat_suggestion SET state='sending', payload=? WHERE id=? AND state='blocked'", json(sending), id) == 1;
    }

    public synchronized int followUpClaimCount(String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boss_chat_suggestion WHERE conversation_id=? AND message_id LIKE 'followup:%'",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    public synchronized Suggestion claimFollowUp(String conversationId, Suggestion claim) {
        Integer inflight = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boss_chat_suggestion WHERE conversation_id=? AND state='sending'",
                Integer.class, conversationId);
        if (inflight != null && inflight > 0) {
            return null;
        }

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boss_chat_suggestion WHERE conversation_id=? AND message_id LIKE 'followup:%'",
                Integer.class, conversationId);
        int round = (count == null ? 0 : count) + 1;
        String messageId = "followup:" + round;

        Suggestion target = new Suggestion(
                claim.id(),
                claim.version(),
                conversationId,
                messageId,
                claim.profileVersion(),
                claim.state(),
                claim.replyText(),
                claim.intent(),
                claim.action(),
                claim.reasonCode(),
                claim.missingFacts(),
                claim.sourceText(),
                claim.resumeAssetId(),
                claim.evidenceFactIds(),
                claim.createdAt()
        );

        int changed = jdbc.update(
                "INSERT OR IGNORE INTO boss_chat_suggestion(id,conversation_id,message_id,state,payload) VALUES(?,?,?,'sending',?)",
                target.id(), conversationId, messageId, json(target));

        return changed > 0 ? target : null;
    }

    public synchronized int followUpCount(String conversationId) {
        var rows = jdbc.queryForList("SELECT count FROM boss_chat_followup WHERE conversation_id=?", Integer.class, conversationId);
        return rows.isEmpty() || rows.getFirst() == null ? 0 : rows.getFirst();
    }

    public synchronized Long followUpLastAt(String conversationId) {
        var rows = jdbc.queryForList("SELECT last_sent_at FROM boss_chat_followup WHERE conversation_id=?", Long.class, conversationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public synchronized void recordFollowUpSend(String conversationId, long at) {
        jdbc.update("INSERT INTO boss_chat_followup(conversation_id, count, last_sent_at) VALUES(?, 1, ?) " +
                        "ON CONFLICT(conversation_id) DO UPDATE SET count=count+1, last_sent_at=excluded.last_sent_at",
                conversationId, at);
    }

    public synchronized Long latestInboundAt(String conversationId) {
        var rows = jdbc.queryForList("SELECT MAX(received_at) FROM boss_chat_inbound WHERE conversation_id=?", Long.class, conversationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public synchronized String latestInboundText(String conversationId) {
        try {
            var rows = jdbc.queryForList("SELECT payload FROM boss_chat_inbound WHERE conversation_id=? ORDER BY received_at DESC LIMIT 1", String.class, conversationId);
            if (rows.isEmpty()) return null;
            Inbound in = read(rows.getFirst(), Inbound.class);
            return in.sourceText();
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized Long latestOutboundAt(String conversationId) {
        try {
            var rows = jdbc.queryForList("SELECT MAX(CAST(json_extract(payload, '$.createdAt') AS INTEGER)) FROM boss_chat_suggestion WHERE conversation_id=? AND state IN ('sent','unknown')", Long.class, conversationId);
            return rows.isEmpty() ? null : rows.getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean hasUnsettledSend(String conversationId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM boss_chat_suggestion WHERE conversation_id=? AND state IN ('sending','unknown')", Integer.class, conversationId);
        return count != null && count > 0;
    }

    public synchronized Inbound latestInbound(String conversationId) {
        try {
            var rows = jdbc.queryForList("SELECT payload FROM boss_chat_inbound WHERE conversation_id=? ORDER BY received_at DESC LIMIT 1", String.class, conversationId);
            if (rows.isEmpty()) return null;
            return read(rows.getFirst(), Inbound.class);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized Set<String> allFollowUpCandidateConversationIds() {
        Set<String> set = new LinkedHashSet<>();
        set.addAll(jdbc.queryForList("SELECT DISTINCT conversation_id FROM boss_chat_inbound", String.class));
        set.addAll(jdbc.queryForList("SELECT DISTINCT conversation_id FROM boss_chat_suggestion", String.class));
        return set;
    }

    public synchronized boolean isLatest(Suggestion s) {
        var rows = jdbc.queryForList("SELECT message_id FROM boss_chat_inbound WHERE conversation_id=? ORDER BY rowid DESC LIMIT 1", String.class, s.conversationId());
        return !rows.isEmpty() && rows.getFirst().equals(s.lastInboundMessageId());
    }

    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        jdbc.query("SELECT state,COUNT(*) FROM boss_chat_suggestion GROUP BY state", r -> { result.put(r.getString(1), r.getInt(2)); });
        result.put("observedMessages", jdbc.queryForObject("SELECT COUNT(*) FROM boss_chat_inbound", Integer.class));
        return result;
    }

    private Suggestion withState(Suggestion s, String state) {
        return new Suggestion(s.id(), s.version(), s.conversationId(), s.lastInboundMessageId(), s.profileVersion(), state, s.replyText(), s.intent(), s.action(), s.reasonCode(), s.missingFacts(), s.sourceText(), s.resumeAssetId(), s.evidenceFactIds(), s.createdAt());
    }

    private String json(Object o) { try { return mapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String s, Class<T> c) { try { return mapper.readValue(s, c); } catch (Exception e) { throw new IllegalStateException(e); } }
}
