package com.example.githubaction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.github")
public class GithubProps {
    private String owner;
    private String repo;
    private String token;      // public repo면 null/빈값 가능
    private String targetPath; // 예: a.txt 또는 config/a.txt

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
}
