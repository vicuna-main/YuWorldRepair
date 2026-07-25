package dev.yu.worldrepair.log;

import dev.yu.worldrepair.guard.ErrorDomain;

public enum KnownErrorRule {
    AE_KEY(
            1,
            ErrorDomain.AE_KEY,
            "appeng.api.stacks.AEKey",
            "Failed to deserialize AE key: {}",
            "Failed to deserialize AE key: ",
            ""
    ),
    AE_ITEM_STACK(
            2,
            ErrorDomain.ITEM_STACK,
            "appeng.util.AECodecs",
            "Failed to deserialize ItemStack: {}",
            "Failed to deserialize ItemStack: ",
            ""
    ),
    MINECRAFT_ITEM_STACK(
            3,
            ErrorDomain.ITEM_STACK,
            "net.minecraft.world.item.ItemStack",
            "Tried to load invalid item: '{}'",
            "Tried to load invalid item: '",
            "'"
    ),
    NEOFORGE_ATTACHMENT(
            4,
            ErrorDomain.ATTACHMENT,
            "net.neoforged.neoforge.attachment.AttachmentHolder",
            "Encountered unknown or non-serializable data attachment {}. Skipping.",
            "Encountered unknown or non-serializable data attachment ",
            ". Skipping."
    );

    private final int ruleId;
    private final ErrorDomain domain;
    private final String loggerName;
    private final String template;
    private final String renderedPrefix;
    private final String renderedSuffix;

    KnownErrorRule(
            int ruleId,
            ErrorDomain domain,
            String loggerName,
            String template,
            String renderedPrefix,
            String renderedSuffix
    ) {
        this.ruleId = ruleId;
        this.domain = domain;
        this.loggerName = loggerName;
        this.template = template;
        this.renderedPrefix = renderedPrefix;
        this.renderedSuffix = renderedSuffix;
    }

    public int ruleId() {
        return ruleId;
    }

    public ErrorDomain domain() {
        return domain;
    }

    public String loggerName() {
        return loggerName;
    }

    public String template() {
        return template;
    }

    private boolean matchesMessage(String messageFormat) {
        if (template.equals(messageFormat)) {
            return true;
        }
        int minimumLength = renderedPrefix.length() + renderedSuffix.length() + 1;
        return messageFormat.length() >= minimumLength
                && messageFormat.startsWith(renderedPrefix)
                && messageFormat.endsWith(renderedSuffix);
    }

    public static KnownErrorRule match(String loggerName, String template) {
        if (loggerName == null || template == null) {
            return null;
        }
        return switch (loggerName) {
            case "appeng.api.stacks.AEKey" ->
                    AE_KEY.matchesMessage(template) ? AE_KEY : null;
            case "appeng.util.AECodecs" ->
                    AE_ITEM_STACK.matchesMessage(template) ? AE_ITEM_STACK : null;
            case "net.minecraft.world.item.ItemStack" ->
                    MINECRAFT_ITEM_STACK.matchesMessage(template) ? MINECRAFT_ITEM_STACK : null;
            case "net.neoforged.neoforge.attachment.AttachmentHolder" ->
                    NEOFORGE_ATTACHMENT.matchesMessage(template) ? NEOFORGE_ATTACHMENT : null;
            default -> null;
        };
    }
}
