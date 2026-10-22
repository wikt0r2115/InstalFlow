package architecturefixtures.cycle.left;

import architecturefixtures.cycle.right.RightApi;

public record LeftApi(RightApi right) {}
