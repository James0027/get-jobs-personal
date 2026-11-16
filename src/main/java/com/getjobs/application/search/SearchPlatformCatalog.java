package com.getjobs.application.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.getjobs.application.service.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Reads existing option catalogs; values retain their platform-specific meaning/units. */
@Service
public class SearchPlatformCatalog {
    private final BossService boss;
    private final LiepinService liepin;
    private final Job51Service job51;
    private final ZhilianService zhilian;
    public SearchPlatformCatalog(BossService boss,LiepinService liepin,Job51Service job51,ZhilianService zhilian){this.boss=boss;this.liepin=liepin;this.job51=job51;this.zhilian=zhilian;}
    public Map<String,String> options(String platform,String field){
        String type=field.equals("cityCode")?"city":field;
        Map<String,String> result=new LinkedHashMap<>();
        switch(platform){
            // getOptionsByType seeds defaults; this read-only preview must not mutate catalogs.
            case "BOSS" -> boss.getAllOptions().stream().filter(o->type.equals(o.getType())).forEach(o->result.put(o.getCode(),o.getName()));
            case "LIEPIN" -> liepin.getOptionsByType(type).forEach(o->result.put(o.getCode(),o.getName()));
            case "JOB51" -> job51.getOptionsByType(type).forEach(o->result.put(o.getCode(),o.getName()));
            case "ZHILIAN" -> zhilian.getOptionsByType(type).forEach(o->result.put(o.getCode(),o.getName()));
            default -> throw SearchTemplateStore.invalid("platformId","Unknown platform");
        }
        return result;
    }
    public void validate(String platform,JsonNode fields){
        fields.properties().forEach(e->{
            if(e.getKey().equals("keywords")||e.getValue().isNull())return;
            Map<String,String> values=options(platform,e.getKey());
            Iterable<JsonNode> selections=e.getValue().isArray()?e.getValue():List.of(e.getValue());
            for(JsonNode selection:selections) {
                // Empty scalar means explicitly no filter, matching the existing scalar configuration.
                if(!selection.asText().isEmpty()&&!values.containsKey(selection.asText()))
                    throw SearchTemplateStore.invalid("fields."+e.getKey(),"Unknown platform option code");
            }
        });
    }
}
