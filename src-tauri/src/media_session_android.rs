//! Android media integration — the mobile counterpart of the souvlaki block
//! in `run()`'s setup.
//!
//! Outgoing: the `mpris_set_metadata` / `mpris_set_playback` commands forward
//! their payloads to the Kotlin `MediaBridge` object (gen/android, package
//! `dev.psysonic.player.media`) over JNI; Kotlin renders them into the
//! MediaSession + foreground-service notification.
//!
//! Incoming: MediaSession callbacks, audio-focus changes and headphone/route
//! events come back through the exported
//! `Java_dev_psysonic_player_media_MediaBridge_nativeMediaEvent` symbol
//! (resolved automatically — the JVM searches loaded libraries for `Java_*`
//! exports when Kotlin declares the matching `external fun`). They re-emit
//! the same `media:*` Tauri events the desktop souvlaki attach handler
//! produces, so the frontend bridge (`useMediaAndWindowBridge`) is shared.

use std::sync::OnceLock;

use jni::objects::{JClass, JObject, JString, JValue};
use jni::refs::{Global, LoaderContext};
use jni::{Env, EnvUnowned, JavaVM};
use tauri::{AppHandle, Emitter};

static APP_HANDLE: OnceLock<AppHandle> = OnceLock::new();
static BRIDGE_CLASS: OnceLock<Global<JClass<'static>>> = OnceLock::new();

/// Store the app handle (for event emits from session callbacks) and resolve
/// the `MediaBridge` class once. Class lookup must go through the activity's
/// classloader — `FindClass` from a native thread uses the system loader,
/// which cannot see app classes.
pub(crate) fn init(app: &AppHandle) {
    let _ = APP_HANDLE.set(app.clone());

    let ctx = ndk_context::android_context();
    let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) };
    let result = vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
        // Borrowed wrapper around ndk-context's global activity ref (never
        // dropped as a local — same pattern as init_rustls_platform_verifier).
        let activity = unsafe { JObject::from_raw(env, ctx.context().cast()) };
        let class = LoaderContext::FromObject(&activity).load_class(
            env,
            jni::jni_str!("dev.psysonic.player.media.MediaBridge"),
            true,
        )?;
        let _ = BRIDGE_CLASS.set(env.new_global_ref(&class)?);
        Ok(())
    });
    if let Err(e) = result {
        crate::app_eprintln!("[media-session] MediaBridge class resolve failed: {e}");
    }
}

fn with_bridge<F>(what: &str, f: F)
where
    F: FnOnce(&mut Env, &Global<JClass<'static>>) -> Result<(), jni::errors::Error>,
{
    let Some(class) = BRIDGE_CLASS.get() else { return };
    let vm = unsafe { JavaVM::from_raw(ndk_context::android_context().vm().cast()) };
    let result = vm.attach_current_thread(|env| f(env, class));
    if let Err(e) = result {
        crate::app_eprintln!("[media-session] {what} failed: {e}");
    }
}

fn opt_jstring<'l>(env: &mut Env<'l>, s: Option<&str>) -> Result<JObject<'l>, jni::errors::Error> {
    Ok(match s {
        Some(s) => JString::from_str(env, s)?.into(),
        None => JObject::null(),
    })
}

pub(crate) fn update_metadata(
    title: Option<&str>,
    artist: Option<&str>,
    album: Option<&str>,
    cover_url: Option<&str>,
    duration_secs: Option<f64>,
) {
    with_bridge("updateMetadata", |env, class| {
        let title = opt_jstring(env, title)?;
        let artist = opt_jstring(env, artist)?;
        let album = opt_jstring(env, album)?;
        let cover = opt_jstring(env, cover_url)?;
        env.call_static_method(
            class,
            jni::jni_str!("updateMetadata"),
            jni::jni_sig!("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;D)V"),
            &[
                JValue::Object(&title),
                JValue::Object(&artist),
                JValue::Object(&album),
                JValue::Object(&cover),
                JValue::Double(duration_secs.unwrap_or(0.0)),
            ],
        )?;
        Ok(())
    });
}

pub(crate) fn update_playback(playing: bool, position_secs: Option<f64>) {
    with_bridge("updatePlayback", |env, class| {
        env.call_static_method(
            class,
            jni::jni_str!("updatePlayback"),
            jni::jni_sig!("(ZD)V"),
            &[
                JValue::Bool(playing),
                // Negative = "no position info, keep the last one" (souvlaki's
                // progress: None); Kotlin ignores values below zero.
                JValue::Double(position_secs.unwrap_or(-1.0)),
            ],
        )?;
        Ok(())
    });
}

/// Session callbacks / focus / headphone events from Kotlin. Runs on an
/// arbitrary JVM thread (usually the Android main thread) — `AppHandle::emit`
/// and the audio reopen spawn are both thread-safe.
#[no_mangle]
pub extern "system" fn Java_dev_psysonic_player_media_MediaBridge_nativeMediaEvent<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    event: JString<'caller>,
    value: jni::sys::jdouble,
) {
    let outcome = unowned_env.with_env(|_env| -> Result<(), jni::errors::Error> {
        let event = event.to_string();
        let Some(app) = APP_HANDLE.get() else { return Ok(()) };
        match event.as_str() {
            // Play/Pause stay distinct from Toggle for the same reason as the
            // desktop handler (#1094): focus loss and headphone unplug send an
            // explicit Pause, and a toggle would resume paused playback.
            "play" => { let _ = app.emit("media:play", ()); }
            "pause" => { let _ = app.emit("media:pause", ()); }
            "toggle" => { let _ = app.emit("media:play-pause", ()); }
            "next" => { let _ = app.emit("media:next", ()); }
            "prev" => { let _ = app.emit("media:prev", ()); }
            "stop" => { let _ = app.emit("media:stop", ()); }
            "seek" => { let _ = app.emit("media:seek-absolute", value); }
            "route-changed" => crate::audio::reopen_stream_after_route_change(app.clone()),
            other => crate::app_eprintln!("[media-session] unknown event: {other}"),
        }
        Ok(())
    });
    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}
