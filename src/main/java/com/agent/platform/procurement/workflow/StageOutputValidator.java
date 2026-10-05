package com.agent.platform.procurement.workflow;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Closed, recursive validation of the fixed stage schemas; no lenient repair of model output. */
public final class StageOutputValidator {
    private StageOutputValidator(){}
    public static void validate(JsonNode schema,JsonNode value){check(schema,value,"output");}
    private static void check(JsonNode schema,JsonNode value,String path){
        String type=schema.path("type").asText();
        boolean valid=switch(type){case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();case "boolean"->value.isBoolean();default->!value.isNull();};
        if(!valid)fail(path);
        if(schema.path("enum").isArray()){
            boolean found=false;for(var allowed:schema.path("enum"))if(allowed.equals(value))found=true;if(!found)fail(path+" enum");
        }
        if(value.isObject()){
            for(var required:schema.path("required"))if(!value.has(required.asText()))fail(path+" required "+required.asText());
            value.properties().forEach(e->{var property=schema.path("properties").get(e.getKey());if(property==null){var extra=schema.path("additionalProperties");if(extra.isBoolean()&&!extra.asBoolean())fail(path+" unknown "+e.getKey());if(extra.isObject())check(extra,e.getValue(),path+"."+e.getKey());}else check(property,e.getValue(),path+"."+e.getKey());});
        }
        if(value.isArray()){
            if(value.size()>schema.path("maxItems").asInt(256) || value.size()<schema.path("minItems").asInt(0))fail(path+" count");
            for(var child:value)check(schema.path("items"),child,path+"[]");
        }
        if(value.isNumber()){
            if(schema.has("minimum")&&value.decimalValue().compareTo(schema.path("minimum").decimalValue())<0)fail(path+" minimum");
            if(schema.has("maximum")&&value.decimalValue().compareTo(schema.path("maximum").decimalValue())>0)fail(path+" maximum");
        }
        if(value.isTextual()){
            if(value.asText().length()>schema.path("maxLength").asInt(12000) || value.asText().length()<schema.path("minLength").asInt(0))fail(path+" length");
            if(schema.has("pattern")&&!value.asText().matches(schema.path("pattern").asText()))fail(path+" pattern");
        }
    }
    private static void fail(String path){throw new IllegalArgumentException("stage schema invalid: "+path);}
}
