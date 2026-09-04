package com.vadim.devops.bash;

/**
 * Builds the shell command for a host: ssh-wrapped for remote hosts, plain for local ones.
 * Responsibility: quoting and the local/remote decision, nothing else.
 */
public final class HostCommand {

    private HostCommand() {
    }

    /**
     * Хост считается локальным, если у него нет пригодного ssh-таргета.
     * Строка "null" — распространённый артефакт: LLM передаёт её в saveHost вместо отсутствующего значения.
     */
    public static boolean isLocal(String sshTarget) {
        return sshTarget == null || sshTarget.isBlank() || "null".equalsIgnoreCase(sshTarget.trim());
    }

    /** Обернуть команду в ssh для удалённого хоста; для локального вернуть как есть. */
    public static String wrap(String sshTarget, String command) {
        return isLocal(sshTarget) ? command : "ssh %s %s".formatted(sshTarget.trim(), shellQuote(command));
    }

    public static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
