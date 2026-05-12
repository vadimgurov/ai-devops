package com.vadim.devops.repo;

import com.vadim.devops.bash.BashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class RepoManager {

    private static final Logger log = LoggerFactory.getLogger(RepoManager.class);

    private final BashService bashService;
    private final Path reposDir;

    public RepoManager(BashService bashService,
                       @Value("${devops.repos.path:./repos}") String reposPath) {
        this.bashService = bashService;
        this.reposDir = Path.of(reposPath);
    }

    /**
     * Клонирует репозиторий если его нет, иначе делает git pull.
     * Директория определяется по имени репо из URL — несколько сервисов из одной монорепы
     * используют одну и ту же локальную копию.
     * Возвращает абсолютный путь к клону.
     */
    public String ensureFresh(String repoUrl) throws IOException {
        var repoName = repoNameFrom(repoUrl);
        var localPath = reposDir.resolve(repoName).toAbsolutePath();
        if (Files.exists(localPath.resolve(".git"))) {
            log.info("git pull: {}", localPath);
            bashService.execLocal("git -C %s pull --ff-only".formatted(localPath));
        } else {
            log.info("git clone {} → {}", repoUrl, localPath);
            Files.createDirectories(localPath.getParent());
            bashService.execLocal("git clone %s %s".formatted(repoUrl, localPath));
        }
        return localPath.toString();
    }

    /** git@github.com:user/foo.git → foo, https://github.com/user/foo.git → foo */
    private static String repoNameFrom(String repoUrl) {
        var name = repoUrl.replaceAll(".*/", "").replaceAll("\\.git$", "");
        return name.isEmpty() ? "repo" : name;
    }
}