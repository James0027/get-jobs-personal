package com.getjobs.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.getjobs.application.chat.ChatContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatDecisionValidatorTest {
    ObjectMapper mapper=new ObjectMapper(); ChatDecisionValidator validator=new ChatDecisionValidator(mapper);
    Profile profile=new Profile("1",2027,List.of("上海"),List.of(new Fact("f1","产品经验")),"asset",true,true,true);
    String json(Decision d)throws Exception{return mapper.writeValueAsString(d);}
    Decision valid(){return new Decision("1","r",Intent.OTHER,Action.SUGGEST_REPLY,"具备产品需求分析经验。","",List.of("f1"),List.of(),"MATCH");}
    @Test void acceptsFixedContract()throws Exception{assertEquals("r",validator.parse(json(valid()),"r",profile,60).requestId());}
    @Test void rejectsUnknownFieldsAndUnsupportedFacts()throws Exception{
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(valid()).replaceFirst("\\{","{\"extra\":1,"),"r",profile,60));
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(valid()).replace("f1","invented"),"r",profile,60));
    }
    @Test void rejectsTrailingJsonDuplicateKeysAndLength()throws Exception{
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(valid())+" {}","r",profile,60));
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(valid()).replaceFirst("\\{","{\"requestId\":\"r\","),"r",profile,60));
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(valid()),"r",profile,3));
    }
    @Test void rejectsAutoActionForMixedIntent()throws Exception{
        Decision bad=new Decision("1","r",Intent.MIXED,Action.SEND_RESUME,"","asset",List.of(),List.of(),"MIXED");
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(bad),"r",profile,300));
    }
    @Test void rejectsMissingNullAndUnknownEnumsEvenWithPermissiveMapper()throws Exception{
        String correct=json(valid());
        ChatDecisionValidator permissive=new ChatDecisionValidator(new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL));
        for(String bad:List.of(correct.replace("\"OTHER\"","\"UNRECOGNIZED\""),correct.replace("\"SUGGEST_REPLY\"","\"EXECUTE_ANYTHING\""),correct.replace("\"OTHER\"","null"),correct.replace("\"intent\":\"OTHER\",","")))
            assertThrows(IllegalArgumentException.class,()->permissive.parse(bad,"r",profile,300));
    }
    @Test void rejectsWrongTaskActionsUnapprovedResumeAndUnapprovedPromises()throws Exception{
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(new Decision("1","r",Intent.REQUEST_RESUME,Action.SEND_RESUME,"","asset",List.of(),List.of(),"REQUEST")),"r",profile,60,"MATCH_EXPLANATION"));
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(new Decision("1","r",Intent.REQUEST_RESUME,Action.SEND_RESUME,"","",List.of(),List.of(),"REQUEST")),"r",profile,300));
        for(String text:List.of("我是2028届毕业生。","期望薪资30K。","可以立即到岗。"))
            assertThrows(IllegalArgumentException.class,()->validator.parse(json(new Decision("1","r",Intent.OTHER,Action.SUGGEST_REPLY,text,"",List.of("f1"),List.of(),"REPLY")),"r",profile,300));
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(new Decision("1","r",Intent.OTHER,Action.SUGGEST_REPLY,"","",List.of(),List.of(),"EMPTY")),"r",profile,300));
        assertThrows(IllegalArgumentException.class,()->validator.parse(json(new Decision("1","r",Intent.OTHER,Action.NEEDS_INFORMATION,"","",List.of(),List.of(),"MISSING")),"r",profile,300));
    }
}
