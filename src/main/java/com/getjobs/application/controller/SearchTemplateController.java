package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.application.chat.BossChatStore;
import com.getjobs.application.search.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/search-templates")
public class SearchTemplateController {
    private final SearchTemplateStore store;
    private final SearchPlatformCatalog catalog;
    private final BossChatStore profiles;
    private final ObjectMapper mapper;
    public SearchTemplateController(SearchTemplateStore store,SearchPlatformCatalog catalog,BossChatStore profiles,ObjectMapper mapper){this.store=store;this.catalog=catalog;this.profiles=profiles;this.mapper=mapper;}
    @GetMapping public Object list(){return store.list();}
    @GetMapping("/{id}") public Object get(@PathVariable String id){return store.get(id);}
    @PostMapping public ResponseEntity<?> create(@RequestBody JsonNode body){return ResponseEntity.status(201).body(store.save(null,body));}
    @PutMapping("/{id}") public Object update(@PathVariable String id,@RequestBody JsonNode body){return store.save(id,body);}
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id){store.delete(id);return ResponseEntity.noContent().build();}
    @GetMapping("/{id}/platform-overrides") public Object overrides(@PathVariable String id){
        var template=store.get(id);var saved=store.overrides(id);var profile=profiles.profile();
        List<Object> results=new ArrayList<>();
        for(String platform:List.of("BOSS","LIEPIN","JOB51","ZHILIAN")) {
            var override=saved.stream().filter(o->o.platformId().equals(platform)).findFirst().orElse(null);
            ObjectNode effective=mapper.createObjectNode();Map<String,String> sources=new LinkedHashMap<>();List<String> draft=new ArrayList<>();
            JsonNode criteria=template.criteria();
            JsonNode overlay=override==null?mapper.createObjectNode():override.fields();
            if(!criteria.has("targetCities"))resolveField(platform,"targetCities",mapper.valueToTree(profile.acceptedCities()),"PROFILE",overlay,effective,sources,draft);
            criteria.properties().forEach(e->resolveField(platform,e.getKey(),e.getValue(),"TEMPLATE",overlay,effective,sources,draft));
            if(override!=null)override.fields().properties().forEach(e->{effective.set(e.getKey(),e.getValue());sources.put(e.getKey(),"PLATFORM_OVERRIDE");});
            results.add(Map.of("platformId",platform,"version",override==null?0:override.version(),"fields",override==null?mapper.createObjectNode():override.fields(),
                    "effectiveCriteria",effective,"sources",sources,"draftOnlyFields",draft,"executionSupported",false,
                    "templateVersion",template.version(),"profileVersion",profile.version()));
        }
        return results;
    }
    private void resolveField(String platform,String common,JsonNode value,String source,JsonNode overlay,ObjectNode effective,Map<String,String> sources,List<String> draft){
        String target=switch(common){case "targetCities"->platform.equals("JOB51")?"jobArea":"cityCode";case "companyScale"->"scale";case "financingStage"->"stage";case "workType"->"jobType";default->common;};
        Boolean array=SearchTemplateStore.FIELDS.get(platform).get(target);
        if(array==null){draft.add(common);return;}
        if(overlay.has(target))return;
        if(target.equals("keywords")){effective.set(target,value);sources.put(target,source);return;}
        if(!array&&value.size()!=1){draft.add(common);return;}
        Map<String,String> options=catalog.options(platform,target);var resolved=mapper.createArrayNode();
        for(JsonNode item:value){String code=options.entrySet().stream().filter(e->e.getValue().equals(item.asText())).map(Map.Entry::getKey).findFirst().orElse(null);
            if(code==null){draft.add(common);return;}resolved.add(code);}
        effective.set(target,array?resolved:resolved.get(0));sources.put(target,source);
    }
    @PatchMapping("/{id}/platform-overrides/{platform}") public Object patch(@PathVariable String id,@PathVariable String platform,@RequestBody JsonNode body){
        // Type/unknown-field validation must precede option lookup.
        var allowed=SearchTemplateStore.FIELDS.get(platform);
        if(allowed==null)throw SearchTemplateStore.invalid("platformId","Unknown platform");
        JsonNode fields=body==null?null:body.get("fields");
        if(fields==null||!fields.isObject())throw SearchTemplateStore.invalid("fields","Expected object");
        fields.properties().forEach(e->{Boolean array=allowed.get(e.getKey());if(array==null)throw SearchTemplateStore.invalid("fields."+e.getKey(),"Unknown field");
            if(!e.getValue().isNull() && (array?!e.getValue().isArray():!e.getValue().isTextual()))throw SearchTemplateStore.invalid("fields."+e.getKey(),array?"Expected string array":"Expected string");});
        catalog.validate(platform,fields);
        return store.patch(id,platform,body);
    }
    @ExceptionHandler(SearchTemplateStore.Failure.class) public ResponseEntity<?> failure(SearchTemplateStore.Failure e){return ResponseEntity.status(e.status).body(Map.of("success",false,"error",Map.of("code",e.code,"field",e.field,"message",e.getMessage())));}
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> malformed(){return ResponseEntity.badRequest().body(Map.of("success",false,"error",Map.of("code","INVALID_REQUEST","field","request","message","Malformed JSON")));}
}
