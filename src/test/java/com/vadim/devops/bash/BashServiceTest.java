package com.vadim.devops.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BashServiceTest {

    @Mock BashRunner runner;

    BashService service;

    @BeforeEach
    void setUp() {
        service = new BashService(runner);
    }

    @Test
    void exec_runsCommand() {
        when(runner.run("ssh host 'ls'")).thenReturn(new BashResult(0, "file.txt", ""));

        var result = service.exec("ssh host 'ls'");

        assertThat(result.success()).isTrue();
        assertThat(result.stdout()).isEqualTo("file.txt");
        verify(runner).run("ssh host 'ls'");
    }

    @Test
    void execLocal_runsWithoutSsh() {
        when(runner.run("df -h")).thenReturn(new BashResult(0, "Filesystem...", ""));

        var result = service.execLocal("df -h");

        assertThat(result.success()).isTrue();
        verify(runner).run("df -h");
    }
}
