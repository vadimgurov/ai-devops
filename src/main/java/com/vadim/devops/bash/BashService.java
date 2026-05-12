package com.vadim.devops.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin execution layer between LLM tools and BashRunner.
 * Responsibility: run pre-built shell commands and return results.
 * Does NOT build SSH commands, classify commands, or handle approvals — that is BashTool's job.
 */
@Service
public class BashService {

    private static final Logger log = LoggerFactory.getLogger(BashService.class);

    private final BashRunner runner;

    public BashService(BashRunner runner) {
        this.runner = runner;
    }

    /** Run an already-built command (may include ssh prefix). */
    public BashResult exec(String command) {
        log.debug("bash: {}", command);
        return runner.run(command);
    }

    /** Run a local command on the agent machine. */
    public BashResult execLocal(String command) {
        log.debug("bash local: {}", command);
        return runner.run(command);
    }
}
