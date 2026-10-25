package dev.ironjava.config;

import dev.ironjava.domain.Domain.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ironjava")
public class IronJavaProperties {
    private Role role = Role.CONTROL;
    private boolean workerEnabled = false;
    private boolean controlLoopEnabled = false;
    private final Auth auth = new Auth();
    private final Github github = new Github();
    private final Model model = new Model();
    private final Runner runner = new Runner();
    private final Limits limits = new Limits();
    private List<RepositorySpec> repositories = new ArrayList<>();

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public boolean isControlLoopEnabled() {
        return controlLoopEnabled;
    }

    public void setControlLoopEnabled(boolean controlLoopEnabled) {
        this.controlLoopEnabled = controlLoopEnabled;
    }

    public Auth getAuth() {
        return auth;
    }

    public Github getGithub() {
        return github;
    }

    public Model getModel() {
        return model;
    }

    public Runner getRunner() {
        return runner;
    }

    public Limits getLimits() {
        return limits;
    }

    public List<RepositorySpec> getRepositories() {
        return repositories;
    }

    public void setRepositories(List<RepositorySpec> repositories) {
        this.repositories = repositories;
    }

    public static class Auth {
        private String username = "ironjava";
        private String password = "";
        private String apiToken = "";

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

        public String getApiToken() {
            return apiToken;
        }

        public void setApiToken(String apiToken) {
            this.apiToken = apiToken;
        }
    }

    public static class Github {
        private String appId = "";
        private String privateKeyPath = "";
        private String privateKey = "";
        private String apiBase = "https://api.github.com";

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }
    }

    public static class Model {
        private String mode = "required";
        private String endpoint = "";
        private String name = "";
        private String apiKeyPath = "";
        private String apiKey = "";
        private double inputPricePerMillion = 0;
        private double outputPricePerMillion = 0;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getApiKeyPath() {
            return apiKeyPath;
        }

        public void setApiKeyPath(String apiKeyPath) {
            this.apiKeyPath = apiKeyPath;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

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

    public static class Runner {
        private String mode = "process";
        private String image = "";
        private String mavenBinary = "mvn";
        private String osvScannerBinary = "osv-scanner";
        private String workspaceRoot = "/tmp/ironjava-workspaces";
        private String network = "ironjava_build";
        private String dockerBinary = "docker";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getMavenBinary() {
            return mavenBinary;
        }

        public void setMavenBinary(String mavenBinary) {
            this.mavenBinary = mavenBinary;
        }

        public String getOsvScannerBinary() {
            return osvScannerBinary;
        }

        public void setOsvScannerBinary(String osvScannerBinary) {
            this.osvScannerBinary = osvScannerBinary;
        }

        public String getWorkspaceRoot() {
            return workspaceRoot;
        }

        public void setWorkspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }

        public String getDockerBinary() {
            return dockerBinary;
        }

        public void setDockerBinary(String dockerBinary) {
            this.dockerBinary = dockerBinary;
        }
    }

    public static class Limits {
        private int stageTimeoutSeconds = 900;
        private int leaseSeconds = 60;
        private int maxLogBytes = 200_000;
        private int workspaceRetentionHours = 24;
        private int modelMaxCalls = 2;
        private int modelMaxInputChars = 8_000;
        private int modelMaxOutputTokens = 800;

        public int getStageTimeoutSeconds() {
            return stageTimeoutSeconds;
        }

        public void setStageTimeoutSeconds(int stageTimeoutSeconds) {
            this.stageTimeoutSeconds = stageTimeoutSeconds;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getMaxLogBytes() {
            return maxLogBytes;
        }

        public void setMaxLogBytes(int maxLogBytes) {
            this.maxLogBytes = maxLogBytes;
        }

        public int getWorkspaceRetentionHours() {
            return workspaceRetentionHours;
        }

        public void setWorkspaceRetentionHours(int workspaceRetentionHours) {
            this.workspaceRetentionHours = workspaceRetentionHours;
        }

        public int getModelMaxCalls() {
            return modelMaxCalls;
        }

        public void setModelMaxCalls(int modelMaxCalls) {
            this.modelMaxCalls = modelMaxCalls;
        }

        public int getModelMaxInputChars() {
            return modelMaxInputChars;
        }

        public void setModelMaxInputChars(int modelMaxInputChars) {
            this.modelMaxInputChars = modelMaxInputChars;
        }

        public int getModelMaxOutputTokens() {
            return modelMaxOutputTokens;
        }

        public void setModelMaxOutputTokens(int modelMaxOutputTokens) {
            this.modelMaxOutputTokens = modelMaxOutputTokens;
        }
    }

    public static class RepositorySpec {
        private String id;
        private String displayName = "";
        private String source = "local";
        private String localPath = "";
        private String githubOwner = "";
        private String githubRepo = "";
        private String allowedBranch = "main";
        private long installationId;
        private String buildProfile = "verify";
        private List<String> excludedDependencies = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getLocalPath() {
            return localPath;
        }

        public void setLocalPath(String localPath) {
            this.localPath = localPath;
        }

        public String getGithubOwner() {
            return githubOwner;
        }

        public void setGithubOwner(String githubOwner) {
            this.githubOwner = githubOwner;
        }

        public String getGithubRepo() {
            return githubRepo;
        }

        public void setGithubRepo(String githubRepo) {
            this.githubRepo = githubRepo;
        }

        public String getAllowedBranch() {
            return allowedBranch;
        }

        public void setAllowedBranch(String allowedBranch) {
            this.allowedBranch = allowedBranch;
        }

        public long getInstallationId() {
            return installationId;
        }

        public void setInstallationId(long installationId) {
            this.installationId = installationId;
        }

        public String getBuildProfile() {
            return buildProfile;
        }

        public void setBuildProfile(String buildProfile) {
            this.buildProfile = buildProfile;
        }

        public List<String> getExcludedDependencies() {
            return excludedDependencies;
        }

        public void setExcludedDependencies(List<String> excludedDependencies) {
            this.excludedDependencies = excludedDependencies;
        }
    }
}
