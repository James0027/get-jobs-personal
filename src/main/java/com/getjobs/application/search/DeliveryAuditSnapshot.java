package com.getjobs.application.search;

import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.chat.ChatContracts;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.BossConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Audits actual execution configuration, never substitutes template drafts for it. */
@Service
public class DeliveryAuditSnapshot {
    private final BossChatStore profiles;
    private final SearchTemplateStore templates;
    private final ConfigService configs;
    public DeliveryAuditSnapshot(BossChatStore profiles,SearchTemplateStore templates){this(profiles,templates,null);}
    @Autowired
    public DeliveryAuditSnapshot(BossChatStore profiles,SearchTemplateStore templates,ConfigService configs){
        this.profiles=profiles;this.templates=templates;this.configs=configs;
    }
    public Map<String,Object> capture(BossConfig config,String templateId){
        var profile=profiles.profile();
        SearchTemplateStore.Template template=null;
        if(templateId!=null&&!templateId.isBlank()) {
            try {template=templates.get(templateId);}catch(SearchTemplateStore.Failure e){throw new IllegalArgumentException("关联模板不存在");}
        }
        String appliedId=configValue("TEMPLATE_APPLIED_BOSS_ID");
        String appliedVersion=configValue("TEMPLATE_APPLIED_BOSS_VERSION");
        String appliedFields=configValue("TEMPLATE_APPLIED_BOSS_FIELDS");
        return build(config,profile,template,appliedId,appliedVersion,appliedFields);
    }
    public static Map<String,Object> withoutProfileSource(BossConfig config){return build(config,ChatContracts.Profile.empty(),null,"","","");}
    private static Map<String,Object> build(BossConfig config,ChatContracts.Profile profile,SearchTemplateStore.Template template,
                                            String appliedId,String appliedVersion,String appliedFields){
        Map<String,Object> criteria=new LinkedHashMap<>();List<String> missing=new ArrayList<>();
        put(criteria,missing,"keywords",config.getKeywords());put(criteria,missing,"cityCode",config.getCityCode());
        put(criteria,missing,"salary",config.getSalary());put(criteria,missing,"experience",config.getExperience());
        put(criteria,missing,"degree",config.getDegree());put(criteria,missing,"industry",config.getIndustry());
        put(criteria,missing,"scale",config.getScale());put(criteria,missing,"stage",config.getStage());
        put(criteria,missing,"jobType",config.getJobType());
        List<String> assets=new ArrayList<>();
        if(!profile.approvedResumeAssetId().isBlank())assets.add(profile.approvedResumeAssetId());
        if(!profile.approvedPdfAssetId().isBlank())assets.add(profile.approvedPdfAssetId());
        if(!profile.approvedImageAssetId().isBlank())assets.add(profile.approvedImageAssetId());
        String requestedId=template==null?"":template.id();
        boolean applied=!appliedId.isBlank()&&(requestedId.isBlank()||requestedId.equals(appliedId));
        long appliedVersionNumber=parseLong(appliedVersion);
        Set<String> appliedSet=splitCsv(appliedFields);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion",1);result.put("profileVersion",profile.version());
        result.put("templateId",applied?appliedId:requestedId);
        result.put("templateVersion",applied?appliedVersionNumber:(template==null?0:template.version()));
        result.put("platformId","BOSS");result.put("resolvedCriteria",criteria);result.put("selectedAssetIds",assets);
        result.put("createdAt",Instant.now().toString());result.put("criteriaSource","ACTUAL_BOSS_CONFIG");
        result.put("templateApplicationSupported",applied);result.put("templateApplied",applied);
        result.put("templateApplicationSource",applied?"PLATFORM_CONFIG_APPLY":requestedId.isBlank()?"NONE":"ASSOCIATION_ONLY");
        result.put("appliedTemplateId",applied?appliedId:"");result.put("appliedTemplateVersion",applied?appliedVersionNumber:0);
        List<String> unapplied=new ArrayList<>();
        if(template!=null){
            template.criteria().fieldNames().forEachRemaining(unapplied::add);
            unapplied.add("sayHiDraft");unapplied.add("platformOverrides");
            if(nonblank(template.expectedSalary()))unapplied.add("expectedSalary");
            if(nonblank(template.arrivalIntention()))unapplied.add("arrivalIntention");
            if(nonblank(template.linkedResumeAssetId()))unapplied.add("linkedResumeAssetId");
            if(nonblank(template.personalIntro()))unapplied.add("personalIntro");
            if(nonblank(template.communicationStyle()))unapplied.add("communicationStyle");
        }
        if(appliedSet.contains("cityCode")||appliedSet.contains("jobArea"))unapplied.remove("targetCities");
        if(appliedSet.contains("scale"))unapplied.remove("companyScale");
        if(appliedSet.contains("stage"))unapplied.remove("financingStage");
        if(appliedSet.contains("jobType"))unapplied.remove("workType");
        if(appliedSet.contains("sayHi"))unapplied.remove("sayHiDraft");
        unapplied.removeAll(appliedSet);
        result.put("unappliedTemplateFields",unapplied);result.put("missingCriteriaFields",missing);
        result.put("profileExecutionFrozen",false);result.put("assetExecutionFrozen",false);
        result.put("assetAvailability","NOT_CHECKED");result.put("auditSnapshotStatus","FROZEN");
        result.put("assetIdsSource","PROFILE_APPROVAL_AT_CREATION");
        return result;
    }
    private static void put(Map<String,Object> values,List<String> missing,String key,Object value){
        if(value==null)missing.add(key);else values.put(key,value instanceof List<?> list?new ArrayList<>(list):value);
    }
    private String configValue(String key){
        if(configs==null)return "";
        String value=configs.getConfigValue(key);
        return value==null?"":value;
    }
    private static boolean nonblank(String value){return value!=null&&!value.isBlank();}
    private static long parseLong(String value){
        try{return value==null||value.isBlank()?0:Long.parseLong(value.trim());}catch(NumberFormatException e){return 0;}
    }
    private static Set<String> splitCsv(String value){
        Set<String> result=new LinkedHashSet<>();
        if(value==null||value.isBlank())return result;
        for(String part:value.split(","))if(!part.isBlank())result.add(part.trim());
        return result;
    }
}
