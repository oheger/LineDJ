# Tray Window List

This module adds a tray icon for managing the applications installed on a
LineDJ platform.

## Functionality

The tray icon has a popup menu which lists the titles of all applications
currently installed. The menu items are checkbox items reflecting the
visibility state of the associated applications. By selecting a menu item, the
visibility state of the corresponding application is toggled.

The menu also has an item to shutdown the platform.

## Dependencies

This module depends on the [Window Hiding Application
Manager](../appWindowHiding). Together with this `ApplicationManager`
implementation, full window management can be provided.
