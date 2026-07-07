package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "enterprise-agent.mcp")
public class McpProperties {

    private boolean enabled = false;

    private List<Server> servers = new ArrayList<>();

    private String serverName = "filesystem";

    private String toolNamePrefix = "mcp.filesystem.";

    private String protocolVersion = "2025-11-25";

    private String command = "";

    private List<String> args = new ArrayList<>();

    private String workingDirectory = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers == null ? new ArrayList<>() : new ArrayList<>(servers);
    }

    public List<Server> effectiveServers() {
        if (!servers.isEmpty()) {
            return servers.stream()
                    .filter(Server::isEnabled)
                    .filter(server -> server.getCommand() != null && !server.getCommand().isBlank())
                    .toList();
        }
        if (!enabled || command == null || command.isBlank()) {
            return List.of();
        }
        Server fallback = new Server();
        fallback.setEnabled(true);
        fallback.setServerName(serverName);
        fallback.setToolNamePrefix(toolNamePrefix);
        fallback.setProtocolVersion(protocolVersion);
        fallback.setCommand(command);
        fallback.setArgs(args);
        fallback.setWorkingDirectory(workingDirectory);
        return List.of(fallback);
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getToolNamePrefix() {
        return toolNamePrefix;
    }

    public void setToolNamePrefix(String toolNamePrefix) {
        this.toolNamePrefix = toolNamePrefix;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args == null ? new ArrayList<>() : new ArrayList<>(args);
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public static class Server {

        private boolean enabled = true;

        private String serverName = "";

        private String toolNamePrefix = "";

        private String protocolVersion = "2025-11-25";

        private String command = "";

        private List<String> args = new ArrayList<>();

        private String workingDirectory = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public String getToolNamePrefix() {
            return toolNamePrefix;
        }

        public void setToolNamePrefix(String toolNamePrefix) {
            this.toolNamePrefix = toolNamePrefix;
        }

        public String getProtocolVersion() {
            return protocolVersion;
        }

        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args == null ? new ArrayList<>() : new ArrayList<>(args);
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }
    }
}
