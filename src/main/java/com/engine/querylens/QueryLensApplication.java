package com.engine.querylens;

import com.engine.querylens.infrastructure.cli.QueryLensCli;
import picocli.CommandLine;

/**
 * Main application entry point for QueryLens.
 */
public class QueryLensApplication {

    public static void main(String[] args) {
        // If no arguments provided, default to starting the proxy with defaults
        String[] effectiveArgs = (args == null || args.length == 0) ? new String[]{"proxy"} : args;
        int exitCode = new CommandLine(new QueryLensCli()).execute(effectiveArgs);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
