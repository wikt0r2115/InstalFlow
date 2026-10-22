package pl.instalflow.jobs.api;

/**
 * Public jobs boundary. The implementation belongs to J01.
 * Implementations must create at most one work order for a sourceOfferId and return
 * the existing result when the same accepted offer is submitted again.
 */
public interface Jobs {

    CreatedWorkOrder createFromAcceptedOffer(CreateFromAcceptedOffer command);
}
