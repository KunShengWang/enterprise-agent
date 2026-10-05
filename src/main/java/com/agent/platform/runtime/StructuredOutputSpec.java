package com.agent.platform.runtime;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

/** Trusted, persisted profile setting; never selected from user metadata. */
public record StructuredOutputSpec(Class<?> outputType) {
    public static final String RESULT="_structuredOutput";
    public static final String TYPE="_structuredOutputType";
    public static final String REPAIRS="_structuredOutputRepairs";
    public static final String REPAIR_ATTEMPTED="_structuredRepairAttempted";
    public static final String JSON_MODE="_structuredJsonMode";
    public static final String CORRECTION="_structuredCorrection";
    public static final int MAX_REPEAT_ATTEMPTS=1;
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public interface Validatable { void validateStructure(); }
    public enum Error { STRUCTURED_OUTPUT_PARSE_ERROR, STRUCTURED_OUTPUT_SCHEMA_ERROR, STRUCTURED_OUTPUT_EMPTY, STRUCTURED_OUTPUT_REPAIR_EXHAUSTED }
    public static final class Invalid extends RuntimeException {
        private final Error code;
        public Invalid(Error code,String message){super(message);this.code=code;}
        public Error code(){return code;}
    }
    private BeanOutputConverter<?> converter(){return new BeanOutputConverter<>(outputType,JSON,text->text);}
    public String schema(){return converter().getJsonSchema();}
    public Object parse(String text){
        if(text==null || text.isBlank())throw new Invalid(Error.STRUCTURED_OUTPUT_EMPTY,"empty final assistant content");
        tools.jackson.databind.JsonNode tree;
        try {tree=JSON.readTree(text);}catch(RuntimeException ex){throw new Invalid(Error.STRUCTURED_OUTPUT_PARSE_ERROR,"complete response must be one JSON object");}
        if(tree==null || !tree.isObject())throw new Invalid(Error.STRUCTURED_OUTPUT_SCHEMA_ERROR,"final result must be an object");
        var schema=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(JSON.readTree(schema()));
        var errors=schema.validate(tree);
        if(!errors.isEmpty())throw new Invalid(Error.STRUCTURED_OUTPUT_SCHEMA_ERROR,errors.get(0).getMessage());
        try {
            Object typed=converter().convert(text);
            if(typed instanceof Validatable validation)validation.validateStructure();
            return typed;
        }catch(RuntimeException ex){throw new Invalid(Error.STRUCTURED_OUTPUT_SCHEMA_ERROR,"typed contract rejected: "+ex.getMessage());}
    }
    public static <T> T stored(AgentRunRecord run,Class<T> type){
        var metadata=run.request().metadata();
        if(!type.getName().equals(metadata.get(TYPE)) || !metadata.containsKey(RESULT))
            throw new IllegalStateException("validated typed stage result missing");
        Object value=metadata.get(RESULT);
        return type.isInstance(value)?type.cast(value):type.cast(new BeanOutputConverter<>(type,JSON,text->text).convert(JSON.writeValueAsString(value)));
    }
    public static Map<String,Object> modelMetadata(Map<String,Object> input,StructuredOutputSpec spec,int repairs){
        var metadata=new java.util.LinkedHashMap<>(input);
        metadata.remove(JSON_MODE);metadata.remove(CORRECTION);
        if(spec!=null){metadata.put(JSON_MODE,true);metadata.put(CORRECTION,repairs>0);}
        return Map.copyOf(metadata);
    }
}
