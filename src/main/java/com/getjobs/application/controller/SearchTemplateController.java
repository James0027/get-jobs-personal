package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
    private final SearchTemplateResolver resolver;
    public SearchTemplateController(SearchTemplateStore store,SearchPlatformCatalog catalog,SearchTemplateResolver resolver){this.store=store;this.catalog=catalog;this.resolver=resolver;}
    @GetMapping public Object list(){return store.list();}
    @GetMapping("/{id}") public Object get(@PathVariable String id){return store.get(id);}
    @PostMapping public ResponseEntity<?> create(@RequestBody JsonNode body){return ResponseEntity.status(201).body(store.save(null,body));}
    @PutMapping("/{id}") public Object update(@PathVariable String id,@RequestBody JsonNode body){return store.save(id,body);}
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id){store.delete(id);return ResponseEntity.noContent().build();}
    @GetMapping("/{id}/platform-overrides") public Object overrides(@PathVariable String id){
        List<Object> results=new ArrayList<>();
        for(String platform:List.of("BOSS","LIEPIN","JOB51","ZHILIAN")) {
            var resolved=resolver.resolve(id,platform);
            results.add(Map.of("platformId",resolved.platformId(),"version",resolved.version(),"fields",resolved.fields(),
                    "effectiveCriteria",resolved.effectiveCriteria(),"sources",resolved.sources(),
                    "draftOnlyFields",resolved.draftOnlyFields(),"executionSupported",resolved.executionSupported(),
                    "templateVersion",resolved.templateVersion(),"profileVersion",resolved.profileVersion()));
        }
        return results;
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
