package architecturefixtures.internalaccess.consumer;

import architecturefixtures.internalaccess.provider.internal.InternalComponent;

public class IllegalConsumer {
    private final InternalComponent component;

    public IllegalConsumer(InternalComponent component) {
        this.component = component;
    }
}
