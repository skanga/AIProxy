package com.aiproxy.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;

@Command(name = "serve", description = "Start the proxy server.", mixinStandardHelpOptions = true)
final class ServeCommand implements Callable<Integer> {
    @ParentCommand ProxyCommand root;
    @Mixin ServeOptions options = new ServeOptions();

    @Override public Integer call() throws Exception {
        return root.runServe(options);
    }
}
