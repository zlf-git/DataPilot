package com.zlf.datapilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DataPilot 全局配置类，对应 application.yml 里的 datapilot.* 前缀。
 * 集中管理：业务引擎、LLM、Embedding、Milvus、成本单价。
 */
@ConfigurationProperties(prefix = "datapilot")
public class DatapilotProperties {

    private Engine engine = new Engine();
    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private Milvus milvus = new Milvus();
    private Cost cost = new Cost();
    private Metadata metadata = new Metadata();

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public void setMilvus(Milvus milvus) {
        this.milvus = milvus;
    }

    public Cost getCost() {
        return cost;
    }

    public void setCost(Cost cost) {
        this.cost = cost;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    /** 业务数据执行引擎配置（可插拔：mysql/starrocks） */
    public static class Engine {
        private String type = "mysql";
        private String url;
        private String username;
        private String password;
        /** 引擎侧连接池大小（QA-04 P2-2：此前无池化，每条 SQL 新建 TCP 连接） */
        private int poolSize = 5;
        /** 时间锚点缓存 TTL 秒（QA-04 P2-3：此前硬编码 60s） */
        private int anchorTtlSeconds = 60;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getAnchorTtlSeconds() {
            return anchorTtlSeconds;
        }

        public void setAnchorTtlSeconds(int anchorTtlSeconds) {
            this.anchorTtlSeconds = anchorTtlSeconds;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

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

    /** LLM 配置：provider 决定装配桩(stub)还是真模型(deepseek)；连接信息走 spring.ai.openai.* */
    public static class Llm {
        private String provider = "stub";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }

    /** Embedding 配置（OpenAI 兼容，默认 Qwen text-embedding-v3） */
    public static class Embedding {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String model = "text-embedding-v3";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
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
    }

    /** Milvus 向量库配置（enabled=false 时 Recaller 降级关键词召回） */
    public static class Milvus {
        private boolean enabled = false;
        private String host = "localhost";
        private int port = 19530;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    /** LLM 成本单价（元/百万 token），用于 AgentOps 成本计算 */
    public static class Cost {
        private double inputPricePerMillion = 1.0;
        private double outputPricePerMillion = 2.0;

        public double getInputPricePerMillion() {
            return inputPricePerMillion;
        }

        public void setInputPricePerMillion(double inputPricePerMillion) {
            this.inputPricePerMillion = inputPricePerMillion;
        }

        public double getOutputPricePerMillion() {
            return outputPricePerMillion;
        }

        public void setOutputPricePerMillion(double outputPricePerMillion) {
            this.outputPricePerMillion = outputPricePerMillion;
        }
    }

    /** 元数据写接口开关（Ticket 14 落地鉴权前，写接口默认关） */
    public static class Metadata {
        private boolean writeEnabled = false;

        public boolean isWriteEnabled() {
            return writeEnabled;
        }

        public void setWriteEnabled(boolean writeEnabled) {
            this.writeEnabled = writeEnabled;
        }
    }
}
