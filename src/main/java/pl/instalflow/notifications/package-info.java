/**
 * Notifications module. Other modules may access only explicitly exposed APIs.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED,
        allowedDependencies = {})
package pl.instalflow.notifications;
