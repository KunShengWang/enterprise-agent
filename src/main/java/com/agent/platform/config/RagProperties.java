package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.rag")
public class RagProperties {

    private String mode = "pgvector";

    private String documentDir = "data/rag-docs";

    private String reportDir = "data/rag-reports";

    private int chunkSize = 500;

    private int chunkOverlap = 80;

    private int topK = 3;

    private double minSimilarity = 0.2;

    private final Hybrid hybrid = new Hybrid();

    private final Rerank rerank = new Rerank();

    private final Index index = new Index();

    private final Cache cache = new Cache();

    private final Datasource datasource = new Datasource();

    private final Embedding embedding = new Embedding();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getDocumentDir() {
        return documentDir;
    }

    public void setDocumentDir(String documentDir) {
        this.documentDir = documentDir;
    }

    public String getReportDir() {
        return reportDir;
    }

    public void setReportDir(String reportDir) {
        this.reportDir = reportDir;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = minSimilarity;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public Hybrid getHybrid() {
        return hybrid;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Index getIndex() {
        return index;
    }

    public Cache getCache() {
        return cache;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public static class Hybrid {

        private boolean enabled = true;

        private int vectorCandidateMultiplier = 4;

        private int keywordCandidateLimit = 20;

        private double vectorWeight = 0.7;

        private double keywordWeight = 0.3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getVectorCandidateMultiplier() {
            return vectorCandidateMultiplier;
        }

        public void setVectorCandidateMultiplier(int vectorCandidateMultiplier) {
            this.vectorCandidateMultiplier = vectorCandidateMultiplier;
        }

        public int getKeywordCandidateLimit() {
            return keywordCandidateLimit;
        }

        public void setKeywordCandidateLimit(int keywordCandidateLimit) {
            this.keywordCandidateLimit = keywordCandidateLimit;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public double getKeywordWeight() {
            return keywordWeight;
        }

        public void setKeywordWeight(double keywordWeight) {
            this.keywordWeight = keywordWeight;
        }
    }

    public static class Rerank {

        private boolean enabled = true;

        private double baseScoreWeight = 0.75;

        private double queryCoverageWeight = 0.2;

        private double sourceMatchWeight = 0.05;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getBaseScoreWeight() {
            return baseScoreWeight;
        }

        public void setBaseScoreWeight(double baseScoreWeight) {
            this.baseScoreWeight = baseScoreWeight;
        }

        public double getQueryCoverageWeight() {
            return queryCoverageWeight;
        }

        public void setQueryCoverageWeight(double queryCoverageWeight) {
            this.queryCoverageWeight = queryCoverageWeight;
        }

        public double getSourceMatchWeight() {
            return sourceMatchWeight;
        }

        public void setSourceMatchWeight(double sourceMatchWeight) {
            this.sourceMatchWeight = sourceMatchWeight;
        }
    }

    public static class Index {

        private String type = "hnsw";

        private int ivfflatLists = 100;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getIvfflatLists() {
            return ivfflatLists;
        }

        public void setIvfflatLists(int ivfflatLists) {
            this.ivfflatLists = ivfflatLists;
        }
    }

    public static class Cache {

        private boolean enabled = true;

        private long ttlSeconds = 600;

        private int maxEntries = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    public static class Datasource {

        private String url = "jdbc:postgresql://localhost:5432/enterprise_agent";

        private String username = "postgres";

        private String password = "postgres";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Embedding {

        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

        private String path = "/embeddings";

        private String apiKey = "";

        private String model = "embedding-3";

        private int dimension = 1024;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }
    }
}
