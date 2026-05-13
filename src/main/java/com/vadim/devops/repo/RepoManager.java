package com.vadim.devops.repo;

import com.vadim.devops.bash.BashRunner;
import com.vadim.devops.bash.BashResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class RepoManager {

    private static final Logger log = LoggerFactory.getLogger(RepoManager.class);

    private final BashRunner runner;
    private final Path reposDir;

    public RepoManager(BashRunner runner,
                       @Value("${devops.repos.path:./repos}") String reposPath) {
        this.runner = runner;
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
            var result = git("git", "-C", localPath.toString(), "pull", "--ff-only");
            if (result.exitCode() != 0) {
                throw new IOException("git pull failed (exit %d): %s".formatted(result.exitCode(), result.stderr()));
            }
        } else {
            log.info("git clone {} → {}", repoUrl, localPath);
            Files.createDirectories(localPath.getParent());
            var result = git("git", "clone", repoUrl, localPath.toString());
            if (result.exitCode() != 0) {
                throw new IOException("git clone failed (exit %d): %s".formatted(result.exitCode(), result.stderr()));
            }
        }
        return localPath.toString();
    }

    private BashResult git(String... args) {
        return runner.runArgs(List.of(args), 120);
    }

    /** git@github.com:user/foo.git → foo, https://github.com/user/foo.git → foo */
    private static String repoNameFrom(String repoUrl) {
        var name = repoUrl.replaceAll(".*/", "").replaceAll("\\.git$", "");
        return name.isEmpty() ? "repo" : name;
    }
}