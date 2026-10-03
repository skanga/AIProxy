package com.aiproxy.cli;

import com.aiproxy.util.ApiKeyUtils;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine;

@Command(name = "key", description = "Manage proxy client keys.", subcommands = KeyCommand.Generate.class)
final class KeyCommand implements Runnable {
    public void run() {}

    @Command(name = "generate", description = "Generate a proxy client key.", mixinStandardHelpOptions = true)
    static final class Generate implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Parameters(index = "0", arity = "0..1", paramLabel = "name") String name;
        public Integer call() {
            String key = ApiKeyUtils.generateNewKey();
            spec.commandLine().getOut().println(name == null ? key : name + ":" + key);
            return 0;
        }
    }
}
