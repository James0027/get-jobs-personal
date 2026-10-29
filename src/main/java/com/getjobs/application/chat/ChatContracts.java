package com.getjobs.application.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.List;

/** Versioned wire contracts. Profile values are user-approved facts, never inferred from a JD. */
public final class ChatContracts {
    private ChatContracts() {}
    public record Fact(String id, String text) {}
    public record WorkItem(String id, String company, String title, String period, String summary) {}
    public record ProjectItem(String id, String name, String role, String period, String description) {}
    public record EducationItem(String id, String school, String degree, String major, String period) {}
    public record SkillItem(String id, String name, String level, String description) {}
    public record Resume(List<WorkItem> work, List<ProjectItem> project, List<EducationItem> education, List<SkillItem> skills) {
        public Resume { work=work==null?List.of():List.copyOf(work); project=project==null?List.of():List.copyOf(project);
                        education=education==null?List.of():List.copyOf(education); skills=skills==null?List.of():List.copyOf(skills); }
        public static Resume empty(){ return new Resume(List.of(),List.of(),List.of(),List.of()); }
    }
    public record FollowUp(boolean enabled, Integer cooldownHours, Integer maxPerConversation) {
        public FollowUp { cooldownHours=cooldownHours==null?168:cooldownHours; maxPerConversation=maxPerConversation==null?1:maxPerConversation; }
        public static FollowUp defaults(){ return new FollowUp(false,168,1); }
    }
    public record Profile(
            String version, Integer graduationYear, List<String> acceptedCities,
            List<Fact> facts, String approvedResumeAssetId, boolean autoResume,
            boolean autoLocation, boolean enabled,
            Resume resume, String approvedPdfAssetId, String resumeText, String resumeTextHash,
            String approvedResumeTextHash, List<String> derivedFactIds, boolean autoReply, FollowUp followUp
    ) {
        @JsonCreator
        public Profile(
                @JsonProperty("version") String version,
                @JsonProperty("graduationYear") Integer graduationYear,
                @JsonProperty("acceptedCities") List<String> acceptedCities,
                @JsonProperty("facts") List<Fact> facts,
                @JsonProperty("approvedResumeAssetId") String approvedResumeAssetId,
                @JsonProperty("autoResume") boolean autoResume,
                @JsonProperty("autoLocation") boolean autoLocation,
                @JsonProperty("enabled") boolean enabled,
                @JsonProperty("resume") Resume resume,
                @JsonProperty("approvedPdfAssetId") String approvedPdfAssetId,
                @JsonProperty("resumeText") String resumeText,
                @JsonProperty("resumeTextHash") String resumeTextHash,
                @JsonProperty("approvedResumeTextHash") String approvedResumeTextHash,
                @JsonProperty("derivedFactIds") List<String> derivedFactIds,
                @JsonProperty("autoReply") boolean autoReply,
                @JsonProperty("followUp") FollowUp followUp
        ) {
            this.version = version == null ? "" : version;
            this.graduationYear = graduationYear;
            this.acceptedCities = acceptedCities == null ? List.of() : List.copyOf(acceptedCities);
            this.facts = facts == null ? List.of() : List.copyOf(facts);
            this.approvedResumeAssetId = approvedResumeAssetId == null ? "" : approvedResumeAssetId;
            this.autoResume = autoResume;
            this.autoLocation = autoLocation;
            this.enabled = enabled;
            this.resume = resume == null ? Resume.empty() : resume;
            this.approvedPdfAssetId = approvedPdfAssetId == null ? "" : approvedPdfAssetId;
            this.resumeText = resumeText == null ? "" : resumeText;
            this.resumeTextHash = resumeTextHash == null ? "" : resumeTextHash;
            this.approvedResumeTextHash = approvedResumeTextHash == null ? "" : approvedResumeTextHash;
            this.derivedFactIds = derivedFactIds == null ? List.of() : List.copyOf(derivedFactIds);
            this.autoReply = autoReply;
            this.followUp = followUp == null ? FollowUp.defaults() : followUp;
        }

        public Profile(String version, Integer graduationYear, List<String> acceptedCities,
                       List<Fact> facts, String approvedResumeAssetId, boolean autoResume,
                       boolean autoLocation, boolean enabled) {
            this(version, graduationYear, acceptedCities, facts, approvedResumeAssetId, autoResume,
                    autoLocation, enabled, Resume.empty(), "", "", "", "", List.of(), false, FollowUp.defaults());
        }

        public static Profile empty() {
            return new Profile("0", null, List.of(), List.of(), "", false, false, false,
                    Resume.empty(), "", "", "", "", List.of(), false, FollowUp.defaults());
        }
    }
    public record Message(String id, String sender, String text) {}
    public record Inbound(String conversationId, String lastInboundMessageId, String sourceText,
                          String title, String jd, List<Message> messages) {}
    public enum Intent { REQUEST_RESUME, CONFIRM_LOCATION, OTHER, MIXED, UNKNOWN }
    public enum Action { SEND_RESUME, CONFIRM_LOCATION, SUGGEST_REPLY, NEEDS_INFORMATION, NO_ACTION, AUTO_REPLY }

    @JsonSerialize(using = DecisionSerializer.class)
    public record Decision(String schemaVersion, String requestId, Intent intent, Action action,
                           String replyText, String resumeAssetId, List<String> evidenceFactIds,
                           List<String> missingFacts, String reasonCode, String resumeAssetKind) {
        @JsonCreator
        public Decision(
                @JsonProperty("schemaVersion") String schemaVersion,
                @JsonProperty("requestId") String requestId,
                @JsonProperty("intent") Intent intent,
                @JsonProperty("action") Action action,
                @JsonProperty("replyText") String replyText,
                @JsonProperty("resumeAssetId") String resumeAssetId,
                @JsonProperty("evidenceFactIds") List<String> evidenceFactIds,
                @JsonProperty("missingFacts") List<String> missingFacts,
                @JsonProperty("reasonCode") String reasonCode,
                @JsonProperty("resumeAssetKind") String resumeAssetKind
        ) {
            this.schemaVersion = schemaVersion == null ? "" : schemaVersion;
            this.requestId = requestId == null ? "" : requestId;
            this.intent = intent;
            this.action = action;
            this.replyText = replyText == null ? "" : replyText;
            this.resumeAssetId = resumeAssetId == null ? "" : resumeAssetId;
            this.evidenceFactIds = evidenceFactIds == null ? List.of() : List.copyOf(evidenceFactIds);
            this.missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
            this.reasonCode = reasonCode == null ? "" : reasonCode;
            this.resumeAssetKind = resumeAssetKind == null ? "" : resumeAssetKind;
        }

        public Decision(String schemaVersion, String requestId, Intent intent, Action action,
                        String replyText, String resumeAssetId, List<String> evidenceFactIds,
                        List<String> missingFacts, String reasonCode) {
            this(schemaVersion, requestId, intent, action, replyText, resumeAssetId, evidenceFactIds, missingFacts, reasonCode, "");
        }
    }

    public static class DecisionSerializer extends JsonSerializer<Decision> {
        @Override
        public void serialize(Decision d, JsonGenerator gen, SerializerProvider sp) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("schemaVersion", d.schemaVersion());
            gen.writeStringField("requestId", d.requestId());
            gen.writePOJOField("intent", d.intent());
            gen.writePOJOField("action", d.action());
            gen.writeStringField("replyText", d.replyText());
            gen.writeStringField("resumeAssetId", d.resumeAssetId());
            gen.writePOJOField("evidenceFactIds", d.evidenceFactIds());
            gen.writePOJOField("missingFacts", d.missingFacts());
            gen.writeStringField("reasonCode", d.reasonCode());
            if ("2".equals(d.schemaVersion())) {
                gen.writeStringField("resumeAssetKind", d.resumeAssetKind());
            }
            gen.writeEndObject();
        }
    }
    public record Suggestion(String id, int version, String conversationId, String lastInboundMessageId,
                             String profileVersion, String state, String replyText, Intent intent,
                             Action action, String reasonCode, List<String> missingFacts, String sourceText,
                             String resumeAssetId, List<String> evidenceFactIds, long createdAt) {}
    public record Confirmation(int version, String lastInboundMessageId, String replyText) {}
    public enum SendResult { SENT, FAILED, UNKNOWN, STALE }
}
