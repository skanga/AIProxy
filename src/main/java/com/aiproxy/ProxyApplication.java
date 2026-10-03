package com.aiproxy;

import com.aiproxy.cli.ProxyCommand;

/** Application launcher. CLI parsing and runtime assembly have separate owners. */
public final class ProxyApplication {
    private ProxyApplication() {}

    public static void main(String[] args) {
        System.exit(ProxyCommand.commandLine().execute(args));
    }
}
