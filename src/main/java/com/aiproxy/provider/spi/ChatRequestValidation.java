package com.aiproxy.provider.spi;

/** Validation of normalized tool declarations and references. */
public final class ChatRequestValidation {
    private ChatRequestValidation() {}

    /** Checks tool references before any protocol-specific transformations. */
    public static void validateToolChoice(ChatRequest request) {
        var names = new java.util.HashSet<String>();
        for (var tool : request.tools()) {
            if (!names.add(tool.name())) throw new ToolChoiceException(Problem.DUPLICATE_NAME, tool.name());
        }
        if (request.toolChoice() instanceof ChatRequest.ToolChoice.Named named && !names.contains(named.name())) {
            throw new ToolChoiceException(Problem.UNKNOWN_NAMED_TOOL, named.name());
        }
        if (request.toolChoice() instanceof ChatRequest.ToolChoice.Required && names.isEmpty()) {
            throw new ToolChoiceException(Problem.REQUIRED_WITHOUT_TOOLS, null);
        }
    }

    public enum Problem { DUPLICATE_NAME, UNKNOWN_NAMED_TOOL, REQUIRED_WITHOUT_TOOLS }

    /** Structured failure lets each wire format retain its own diagnostic wording. */
    public static final class ToolChoiceException extends IllegalArgumentException {
        private final Problem problem;
        private final String toolName;

        private ToolChoiceException(Problem problem, String toolName) {
            super(switch (problem) {
                case DUPLICATE_NAME -> "Duplicate tool name";
                case UNKNOWN_NAMED_TOOL -> "Named tool_choice must identify a declared function";
                case REQUIRED_WITHOUT_TOOLS -> "Required tool_choice needs at least one function";
            });
            this.problem = problem;
            this.toolName = toolName;
        }

        public Problem problem() { return problem; }
        public String toolName() { return toolName; }
    }
}
