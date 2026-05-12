package com.vadim.devops.bash;

public record BashResult(int exitCode, String stdout, String stderr, boolean interrupted) {
    public BashResult(int exitCode, String stdout, String stderr) {
        this(exitCode, stdout, stderr, false);
    }
    public boolean success() { return exitCode == 0; }
}
