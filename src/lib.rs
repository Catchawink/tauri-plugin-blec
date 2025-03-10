#[cfg(target_os = "android")]
mod android;
#[cfg(all(not(target_arch = "wasm32"), not(target_arch = "xtensa")))]
mod commands;
#[cfg(all(not(target_arch = "wasm32"), not(target_arch = "xtensa")))]
mod error;
#[cfg(all(not(target_arch = "wasm32"), not(target_arch = "xtensa")))]
mod handler;

#[cfg(all(not(target_arch = "wasm32"), not(target_arch = "xtensa")))]
mod lib {   
    pub use crate::error::Error;
    pub use crate::handler::Handler;

    use futures::StreamExt;
    use once_cell::sync::OnceCell;
    use tauri::{
        async_runtime,
        plugin::{Builder, TauriPlugin},
        Wry,
    };

    static HANDLER: OnceCell<Handler> = OnceCell::new();

    /// Initializes the plugin.
    /// # Panics
    /// Panics if the handler cannot be initialized.
    pub fn init() -> TauriPlugin<Wry> {
        let handler = async_runtime::block_on(Handler::new()).expect("failed to initialize handler");
        let _ = HANDLER.set(handler);

        #[allow(unused)]
        Builder::new("blec")
            .invoke_handler(crate::commands::commands())
            .setup(|app, api| {
                #[cfg(target_os = "android")]
                crate::android::init(app, api)?;
                async_runtime::spawn(handle_events());
                Ok(())
            })
            .build()
    }

    /// Returns the BLE handler to use blec from rust.
    /// # Errors
    /// Returns an error if the handler is not initialized.
    pub fn get_handler() -> crate::error::Result<&'static Handler> {
        let handler = HANDLER.get().ok_or(crate::error::Error::HandlerNotInitialized)?;
        Ok(handler)
    }

    async fn handle_events() {
        let handler = get_handler().expect("failed to get handler");
        let stream = handler
            .get_event_stream()
            .await
            .expect("failed to get event stream");
        stream
            .for_each(|event| async {
                handler
                    .handle_event(event)
                    .await
                    .expect("failed to handle event");
            })
            .await;
    }
}

#[cfg(all(not(target_arch = "wasm32"), not(target_arch = "xtensa")))]
pub use lib::*;