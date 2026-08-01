//! Mobile stand-in for `tray_runtime`: Android/iOS have no system tray, but the
//! tray state types stay managed so command signatures and `.manage()` calls in
//! `run()` are identical on every platform.

use std::sync::Mutex;

/// Always `None` on mobile — there is no tray icon to hold.
pub(crate) type TrayState = Mutex<Option<std::convert::Infallible>>;

pub(crate) type TrayTooltip = Mutex<String>;

#[derive(Default)]
pub(crate) struct TrayPlaybackState(#[allow(dead_code)] pub(crate) Mutex<String>);

pub(crate) type TrayMenuItemsState = Mutex<Option<std::convert::Infallible>>;

#[derive(Clone, Default)]
pub(crate) struct TrayMenuLabels;

pub(crate) type TrayMenuLabelsState = Mutex<TrayMenuLabels>;
