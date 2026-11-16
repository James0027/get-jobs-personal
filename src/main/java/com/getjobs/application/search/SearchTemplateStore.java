package com.getjobs.application.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Configuration/drafts only; never invokes a platform or sends a message. */
@Service
public class SearchTemplateStore {
    public static final Set<String> CRITERIA = Set.of("targetTitles", "keywords", "excludeKeywords", "targetCities",
            "experience", "degree", "industry", "companyScale", "financingStage", "workType");
    public static final Map<String, Map<String, Boolean>> FIELDS = Map.of(
            "BOSS", Map.of("keywords", true,"cityCode", true,"salary",true,"experience",true,"degree",true,"industry",true,"scale",true,"stage",true,"jobType",false),
            "JOB51", Map.of("keywords",true,"jobArea",true,"salary",true),
            "LIEPIN", Map.of("keywords",true,"cityCode",false,"salary",false),
            "ZHILIAN", Map.of("keywords",true,"cityCode",false,"salary",false));
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    public SearchTemplateStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc=jdbc; this.mapper=mapper;
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    @PostConstruct public void init() {
        tx.executeWithoutResult(s -> new ResourceDatabasePopulator(new ClassPathResource("migrations/search-templates-v1-up.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource())));
        ensureColumn("expected_salary");
        ensureColumn("arrival_intention");
        ensureColumn("linked_resume_asset_id");
        ensureColumn("personal_intro");
        ensureColumn("communication_style");
    }
    private void ensureColumn(String column) {
        boolean present = jdbc.queryForList("PRAGMA table_info(search_template)").stream()
                .anyMatch(row -> column.equals(String.valueOf(row.get("name"))));
        if (!present) jdbc.execute("ALTER TABLE search_template ADD COLUMN " + column + " TEXT");
    }
    public record Template(String id,String name,boolean isDefault,long version,JsonNode criteria,String sayHiDraft,String createdAt,String updatedAt,
                           String expectedSalary,String arrivalIntention,String linkedResumeAssetId,String personalIntro,String communicationStyle) {}
    public record Override(String platformId,long version,JsonNode fields,String updatedAt) {}
    public static class Failure extends RuntimeException {
        public final int status; public final String code,field;
        public Failure(int status,String code,String field,String message){super(message);this.status=status;this.code=code;this.field=field;}
    }
    public static Failure invalid(String field,String message){return new Failure(422,"INVALID_FIELD",field,message);}
    public List<Template> list() {
        return jdbc.query("SELECT * FROM search_template ORDER BY created_at,id",(r,n)->new Template(r.getString("id"),r.getString("name"),r.getInt("is_default")==1,
                r.getLong("version"),read(r.getString("criteria_json")),r.getString("say_hi_draft"),r.getString("created_at"),r.getString("updated_at"),
                r.getString("expected_salary"),r.getString("arrival_intention"),r.getString("linked_resume_asset_id"),
                r.getString("personal_intro"),r.getString("communication_style")));
    }
    public Template get(String id){return list().stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new Failure(404,"NOT_FOUND","id","Template not found"));}
    public synchronized Template save(String id,JsonNode body) {
        requireObject(body,"request");
        rejectUnknown(body,Set.of("name","isDefault","criteria","sayHiDraft","version",
                "expectedSalary","arrivalIntention","linkedResumeAssetId","personalIntro","communicationStyle"),"");
        String name=text(body,"name"); if(name.isBlank())throw invalid("name","Name must not be blank");
        if(!body.path("isDefault").isBoolean())throw invalid("isDefault","Expected boolean");
        JsonNode criteria=body.get("criteria"); requireObject(criteria,"criteria");
        rejectUnknown(criteria,CRITERIA,"criteria."); criteria.properties().forEach(e->validateValue(e.getValue(),true,"criteria."+e.getKey()));
        String draft=text(body,"sayHiDraft");
        String expectedSalary=optionalText(body,"expectedSalary");
        String arrivalIntention=optionalText(body,"arrivalIntention");
        String linkedResumeAssetId=optionalText(body,"linkedResumeAssetId");
        String personalIntro=optionalText(body,"personalIntro");
        String communicationStyle=optionalText(body,"communicationStyle");
        boolean isDefault=body.get("isDefault").booleanValue();
        return tx.execute(s->{
            String now=Instant.now().toString(); String savedId=id==null?UUID.randomUUID().toString():id;
            Template old=id==null?null:get(id);
            if(old!=null && version(body)!=old.version())throw new Failure(409,"VERSION_CONFLICT","version","Reload before saving");
            if(isDefault)jdbc.update("UPDATE search_template SET is_default=0,version=version+1,updated_at=? WHERE is_default=1 AND id<>?",now,savedId);
            if(old==null)jdbc.update("INSERT INTO search_template (id,name,is_default,version,criteria_json,say_hi_draft,created_at,updated_at,expected_salary,arrival_intention,linked_resume_asset_id,personal_intro,communication_style) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    savedId,name,isDefault?1:0,1,criteria.toString(),draft,now,now,expectedSalary,arrivalIntention,linkedResumeAssetId,personalIntro,communicationStyle);
            else if(jdbc.update("UPDATE search_template SET name=?,is_default=?,version=version+1,criteria_json=?,say_hi_draft=?,updated_at=?,expected_salary=?,arrival_intention=?,linked_resume_asset_id=?,personal_intro=?,communication_style=? WHERE id=? AND version=?",
                    name,isDefault?1:0,criteria.toString(),draft,now,expectedSalary,arrivalIntention,linkedResumeAssetId,personalIntro,communicationStyle,id,old.version())!=1)throw new Failure(409,"VERSION_CONFLICT","version","Reload before saving");
            return get(savedId);
        });
    }
    public synchronized void delete(String id) {tx.executeWithoutResult(s->{get(id);jdbc.update("DELETE FROM search_template_override WHERE template_id=?",id);jdbc.update("DELETE FROM search_template WHERE id=?",id);});}
    public List<Override> overrides(String id){get(id);return jdbc.query("SELECT * FROM search_template_override WHERE template_id=? ORDER BY platform_id",
            (r,n)->new Override(r.getString("platform_id"),r.getLong("version"),read(r.getString("fields_json")),r.getString("updated_at")),id);}
    public synchronized Override patch(String id,String platform,JsonNode body) {
        Map<String,Boolean> allowed=FIELDS.get(platform); if(allowed==null)throw invalid("platformId","Unknown platform");
        requireObject(body,"request");rejectUnknown(body,Set.of("version","fields"),"");long expected=version(body);
        JsonNode fields=body.get("fields");requireObject(fields,"fields");rejectUnknown(fields,allowed.keySet(),"fields.");
        fields.properties().forEach(e->{if(!e.getValue().isNull())validateValue(e.getValue(),allowed.get(e.getKey()),"fields."+e.getKey());});
        return tx.execute(s->{
            Override old=overrides(id).stream().filter(o->o.platformId().equals(platform)).findFirst().orElse(null);
            if(expected!=(old==null?0:old.version()))throw new Failure(409,"VERSION_CONFLICT","version","Reload before saving");
            ObjectNode saved=old==null?mapper.createObjectNode():((ObjectNode)old.fields()).deepCopy();
            fields.properties().forEach(e->{if(e.getValue().isNull())saved.remove(e.getKey());else saved.set(e.getKey(),e.getValue());});
            String now=Instant.now().toString(); long next=expected+1;
            jdbc.update("INSERT INTO search_template_override VALUES(?,?,?,?,?) ON CONFLICT(template_id,platform_id) DO UPDATE SET version=excluded.version,fields_json=excluded.fields_json,updated_at=excluded.updated_at",
                    id,platform,next,saved.toString(),now);
            return new Override(platform,next,saved,now);
        });
    }
    private JsonNode read(String json){try{return mapper.readTree(json);}catch(Exception e){throw new IllegalStateException("Invalid stored template JSON");}}
    private static String text(JsonNode body,String key){if(!body.path(key).isTextual())throw invalid(key,"Expected string");return body.get(key).textValue();}
    private static String optionalText(JsonNode body,String key){
        JsonNode value=body.get(key);
        if(value==null||value.isNull())return null;
        if(!value.isTextual())throw invalid(key,"Expected string or null");
        return value.textValue();
    }
    private static long version(JsonNode body){if(!body.path("version").isIntegralNumber()||!body.path("version").canConvertToLong()||body.get("version").longValue()<0)throw invalid("version","Expected nonnegative integer");return body.get("version").longValue();}
    private static void requireObject(JsonNode value,String field){if(value==null||!value.isObject())throw invalid(field,"Expected object");}
    private static void rejectUnknown(JsonNode node,Set<String> allowed,String prefix){node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid(prefix+k,"Unknown field");});}
    private static void validateValue(JsonNode value,boolean list,String field){
        if(list){if(!value.isArray())throw invalid(field,"Expected string array");for(JsonNode v:value)if(!v.isTextual()||v.textValue().isBlank())throw invalid(field,"Expected nonblank string elements");}
        else if(!value.isTextual())throw invalid(field,"Expected string");
    }
}
