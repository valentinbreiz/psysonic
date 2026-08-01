//! Mobile stand-in for `sync::tray`: no system tray, no compositing-mode env
//! and no tiling WMs on Android/iOS. The commands stay registered with the same
//! signatures so the frontend and the specta bindings are platform-independent;
//! they just no-op.

use crate::tray_runtime::{
    TrayMenuItemsState, TrayMenuLabelsState, TrayPlaybackState, TrayState, TrayTooltip,
};

pub(crate) use crate::audio::stop_audio_engine;

#[tauri::command]
#[specta::specta]
pub(crate) fn set_tray_tooltip(
    app: tauri::AppHandle,
    tray_state: tauri::State<TrayState>,
    tooltip_cache: tauri::State<TrayTooltip>,
    playback_state_cache: tauri::State<TrayPlaybackState>,
    tooltip: String,
    playback_state: Option<String>,
) -> Result<(), String> {
    let _ = (
        app,
        tray_state,
        tooltip_cache,
        playback_state_cache,
        tooltip,
        playback_state,
    );
    Ok(())
}

#[tauri::command]
#[specta::specta]
#[allow(clippy::too_many_arguments)]
pub(crate) fn set_tray_menu_labels(
    app: tauri::AppHandle,
    labels_state: tauri::State<TrayMenuLabelsState>,
    items_state: tauri::State<TrayMenuItemsState>,
    tooltip_cache: tauri::State<TrayTooltip>,
    play_pause: String,
    next: String,
    previous: String,
    show_hide: String,
    quit: String,
    nothing_playing: String,
) -> Result<(), String> {
    let _ = (app, labels_state, items_state, tooltip_cache);
    let _ = (play_pause, next, previous, show_hide, quit, nothing_playing);
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub(crate) fn toggle_tray_icon(
    app: tauri::AppHandle,
    tray_state: tauri::State<TrayState>,
    show: bool,
) -> Result<(), String> {
    let _ = (app, tray_state, show);
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub(crate) fn no_compositing_mode() -> bool {
    false
}

#[tauri::command]
#[specta::specta]
pub(crate) fn linux_xdg_session_type() -> String {
    String::new()
}

#[tauri::command]
#[specta::specta]
pub(crate) fn is_tiling_wm_cmd() -> bool {
    false
}
