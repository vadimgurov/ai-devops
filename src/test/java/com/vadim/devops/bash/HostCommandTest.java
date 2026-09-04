package com.vadim.devops.bash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostCommandTest {

    @Test
    void wrapsRemoteHostInSsh() {
        assertThat(HostCommand.wrap("ubuntu@example.com", "df -h /"))
                .isEqualTo("ssh ubuntu@example.com 'df -h /'");
    }

    @Test
    void escapesSingleQuotesForBash() {
        assertThat(HostCommand.wrap("ubuntu@example.com", "awk '{print $1}'"))
                .isEqualTo("ssh ubuntu@example.com 'awk '\"'\"'{print $1}'\"'\"''");
    }

    @Test
    void runsLocallyWhenSshTargetMissing() {
        assertThat(HostCommand.wrap(null, "df -h /")).isEqualTo("df -h /");
        assertThat(HostCommand.wrap("", "df -h /")).isEqualTo("df -h /");
        assertThat(HostCommand.wrap("   ", "df -h /")).isEqualTo("df -h /");
    }

    @Test
    void runsLocallyWhenSshTargetIsLiteralNullString() {
        // LLM пишет строку "null" в saveHost вместо отсутствующего значения — это не хостнейм
        assertThat(HostCommand.wrap("null", "df -h /")).isEqualTo("df -h /");
        assertThat(HostCommand.wrap("NULL", "df -h /")).isEqualTo("df -h /");
        assertThat(HostCommand.isLocal("null")).isTrue();
        assertThat(HostCommand.isLocal("ubuntu@example.com")).isFalse();
    }

    @Test
    void shellQuoteEscapesSingleQuotes() {
        assertThat(HostCommand.shellQuote("a'b'c")).isEqualTo("'a'\"'\"'b'\"'\"'c'");
    }
}
