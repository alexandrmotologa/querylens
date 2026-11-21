package com.engine.querylens.infrastructure.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLensCliTest {

    @Test
    void testVersionCommand() {
        QueryLensCli.VersionCommand versionCommand = new QueryLensCli.VersionCommand();
        int exitCode = versionCommand.call();
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void testCliWithVersionFlag() {
        QueryLensCli cli = new QueryLensCli();
        CommandLine cmd = new CommandLine(cli);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--version");
        assertThat(exitCode).isEqualTo(0);
        assertThat(sw.toString()).contains("QueryLens 1.0.0");
    }

    @Test
    void testCliWithShortVersionFlag() {
        QueryLensCli cli = new QueryLensCli();
        CommandLine cmd = new CommandLine(cli);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("-v");
        assertThat(exitCode).isEqualTo(0);
        assertThat(sw.toString()).contains("QueryLens 1.0.0");
    }

    @Test
    void testCliWithVersionSubcommand() {
        QueryLensCli cli = new QueryLensCli();
        CommandLine cmd = new CommandLine(cli);

        int exitCode = cmd.execute("version");
        assertThat(exitCode).isEqualTo(0);
    }
}
