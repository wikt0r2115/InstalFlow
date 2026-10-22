package architecturefixtures.cycle.right;

import architecturefixtures.cycle.left.LeftApi;

public record RightApi(LeftApi left) {}
