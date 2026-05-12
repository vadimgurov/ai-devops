package com.vadim.devops.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vadim.devops.config.DevopsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandRegistryTest {

    @TempDir Path tempDir;

    CommandRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        var yaml = """
                readOnlyPrefixes:
                  - "systemctl status"
                  - "journalctl"
                  - "mysql -e"
                  - "docker inspect"
                  - "jcmd"
                  - "jmap"
                  - "pgrep"
                  - "which"
                  - "readlink"
                  - "cat"
                  - "python3 -m json.tool"
                writeActionPrefixes:
                  - "systemctl restart"
                pipeFilters:
                  - "grep"
                  - "tail"
                  - "head"
                  - "tr"
                stripPrefixes:
                  - "sudo"
                  - "timeout"
                """;
        Files.writeString(tempDir.resolve("allowed_commands.yaml"), yaml);

        var props = mock(DevopsProperties.class);
        var kbProps = mock(DevopsProperties.KbProperties.class);
        when(props.kb()).thenReturn(kbProps);
        when(kbProps.path()).thenReturn(tempDir.toString());

        ObjectMapper yamlMapper = new YAMLMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        registry = new CommandRegistry(props, yamlMapper);
        registry.load();
    }

    @Test
    void classify_readOnly() {
        assertThat(registry.classify("systemctl status billing-api"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_writeAction() {
        assertThat(registry.classify("systemctl restart billing-api"))
                .isEqualTo(CommandRegistry.Classification.WRITE_ACTION);
    }

    @Test
    void classify_unknown() {
        assertThat(registry.classify("unknown_cmd foo"))
                .isEqualTo(CommandRegistry.Classification.UNKNOWN);
    }

    @Test
    void classify_withPipeFilter_readOnly() {
        assertThat(registry.classify("journalctl -u app | grep error"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_journalctl_withSinceUntilAndGrep_readOnly() {
        // Реальный кейс: --since/--until + grep — должен быть read-only без записи в yaml
        var cmd = "journalctl -u crm.service --since '2026-05-12 15:10:00' --until '2026-05-12 15:18:00'" +
                  " 2>/dev/null | grep -B5 -A5 'GOAWAY received'";
        assertThat(registry.classify(cmd)).isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_withSudo_stripsWrapper() {
        assertThat(registry.classify("sudo systemctl status app"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void addReadOnlyPrefix_persistsAndClassifies() {
        registry.addReadOnlyPrefix("df -h");

        assertThat(registry.allReadOnly()).contains("df");
        assertThat(registry.classify("df -h")).isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void addReadOnlyPrefix_duplicate_isIdempotent() {
        registry.addReadOnlyPrefix("df -h");
        registry.addReadOnlyPrefix("df -h");

        long count = registry.allReadOnly().stream().filter("df"::equals).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void classify_mysqlWithDatabaseNameAndOption_isReadOnly() {
        assertThat(registry.classify("sudo mysql crm -e \"SELECT COUNT(*) FROM mailing_queue\" 2>/dev/null"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void addReadOnlyPrefix_mysqlWithDatabaseName_normalizesToStableKey() {
        registry.addReadOnlyPrefix("sudo mysql crm -e \"SELECT COUNT(*) FROM mailing_queue\" 2>/dev/null");

        assertThat(registry.allReadOnly()).contains("mysql -e");
        assertThat(registry.allReadOnly()).doesNotContain("mysql crm");
    }

    @Test
    void classify_commandSubstitutionInArgument_isReadOnly() {
        assertThat(registry.classify("cat /proc/$(pgrep -f crm-1.0.jar | head -1)/cmdline 2>/dev/null | tr '\\0' ' '"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_assignmentWithCommandSubstitutionThenReadOnlyCommand_isReadOnly() {
        assertThat(registry.classify("PID=$(pgrep -f 'crm-1.0.jar' | head -1) && jcmd $PID VM.flags 2>&1"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_sqlWithSemicolonsInsideQuotes_staysReadOnly() {
        assertThat(registry.classify(
                "sudo mysql crm -e \"SELECT COUNT(*) AS total_products FROM product; SELECT COUNT(*) AS with_price_zero FROM product WHERE price = 0;\" 2>/dev/null"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_dockerInspectFormatWithPipe_doesNotBreakOnQuotedTemplate() {
        assertThat(registry.classify("docker inspect crm_reports --format '{{.State.Status}}' | grep -q running"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_readOnlyCommandAfterPipe_isAllowed() {
        assertThat(registry.classify("cat /tmp/sample.json | python3 -m json.tool 2>/dev/null"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void classify_whichAndReadlinkWithNestedSubstitution_isReadOnly() {
        assertThat(registry.classify("which java && readlink -f $(which java)"))
                .isEqualTo(CommandRegistry.Classification.READ_ONLY);
    }

    @Test
    void addReadOnlyPrefix_pythonModuleInvocation_persistsSpecificKey() {
        registry.addReadOnlyPrefix("cat /tmp/sample.json | python3 -m json.tool 2>/dev/null");

        assertThat(registry.allReadOnly()).contains("python3 -m json.tool");
    }

    @Test
    void addReadOnlyPrefix_sleepThenJmap_skipsSleepAndKeepsUsefulCommand() {
        registry.addReadOnlyPrefix("sleep 10 && jmap -clstats 213049 2>&1 | grep -i DelegatingClassLoader | wc -l");

        assertThat(registry.allReadOnly()).contains("jmap");
        assertThat(registry.allReadOnly()).doesNotContain("sleep");
    }

    @Test
    void classify_writeActionInsideCommandSubstitution_isUnknown() {
        assertThat(registry.classify("cat /tmp/$(rm -rf /)/file"))
                .isEqualTo(CommandRegistry.Classification.UNKNOWN);
    }
}
