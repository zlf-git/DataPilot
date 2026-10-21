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

    /**
     * LLM 配置（Ticket 08）：{@code provider} 是切模型的<b>唯一开关</b>。
     * <ul>
     *   <li>{@code stub}：离线桩，读 {@code stub_ir/*.json}，不连网、不需要 Key（Ticket 01 装配）；</li>
     *   <li>{@code deepseek} / {@code qwen}：真实 provider，由 {@code LlmClientFactory} 按本段配置产出
     *       OpenAI 兼容客户端，<b>不再依赖 {@code spring.ai.openai.*}</b>。</li>
     * </ul>
     * {@code baseUrl} / {@code model} / {@code apiKey} 留空 = 用 provider 默认值
     * （见 {@code LlmProvider}），所以切 provider 只改一行；私有化部署、代理网关、换模型版本
     * 则填这三项，同样不改代码。
     */
    public static class Llm {
        private String provider = "stub";
        /** 留空 → provider 默认端点（deepseek: api.deepseek.com；qwen: dashscope compatible-mode） */
        private String baseUrl;
        /** 留空 → provider 默认模型（deepseek-chat / qwen-plus） */
        private String model;
        /** 留空 → 读 provider 对应的环境变量（DEEPSEEK_API_KEY / DASHSCOPE_API_KEY） */
        private String apiKey;
        /** 采样温度：问数场景要可复现，默认 0 */
        private double temperature = 0.0;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
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

    /**
     * 元数据写接口的<b>运维急停</b>（Ticket 14 起语义变更）。
     *
     * <p>Ticket 14 之前它是「没有鉴权时先把写接口关掉」的兜底，默认 {@code false}；
     * 鉴权落地后缺省打开 —— 因为「能不能写」已经由角色决定，再默认关着只会让管理员也写不了。
     * 现在它的用途只剩一个：演示前想冻住所有写入时，置 {@code false} 即可，不必改代码。
     */
    public static class Metadata {
        private boolean writeEnabled = true;

        public boolean isWriteEnabled() {
            return writeEnabled;
        }

        public void setWriteEnabled(boolean writeEnabled) {
            this.writeEnabled = writeEnabled;
        }
    }
}
