package org.gtlcore.gtlcore.config;

public enum AEGraphInventoryLockBehavior {

    ORIGINAL("config.gtlcore.option.ae2GraphInventoryLockBehavior.original"),
    DYNAMIC("config.gtlcore.option.ae2GraphInventoryLockBehavior.dynamic");

    private final String translationKey;

    AEGraphInventoryLockBehavior(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }
}
