# One for all Shutdown Application Manager

This module provides a special implementation of the `ApplicationManager`
service which shuts down the whole platform when one of the applications is
closed.

This is a pretty straight-forward implementation of an application manager. It
is appropriate for platform deployments in which there is only a single
application or if the applications available work together closely, so that it
does not make sense to stop a single one.
