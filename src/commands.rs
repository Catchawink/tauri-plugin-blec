use std::collections::HashMap;

use btleplug::api::Characteristic;
use tauri::ipc::Channel;
use tauri::{async_runtime, command, AppHandle, Runtime};
use tokio::sync::mpsc;
use tracing::info;
use uuid::Uuid;

use crate::error::Result;
use crate::get_handler;
use btleplug::models::{BleDevice, ScanFilter, Service, WriteType};

#[command]
pub(crate) async fn scan<R: Runtime>(
    _app: AppHandle<R>,
    timeout: u64,
    services: Vec<Uuid>,
    on_devices: Channel<Vec<BleDevice>>,
) -> Result<()> {
    tracing::info!("Scanning for BLE devices");
    let handler = get_handler()?;
    let (tx, mut rx) = tokio::sync::mpsc::channel(1);
    
    async_runtime::spawn(async move {
        while let Some(devices) = rx.recv().await {
            on_devices
                .send(devices)
                .expect("failed to send device to the front-end");
        }
    });
    handler
        .discover(Some(tx), timeout, ScanFilter::AnyService(services))
        .await?;
    Ok(())
}

#[command]
pub(crate) async fn stop_scan<R: Runtime>(_app: AppHandle<R>) -> Result<()> {
    tracing::info!("Stopping BLE scan");
    let handler = get_handler()?;
    handler.stop_scan().await?;
    Ok(())
}

#[command]
pub(crate) async fn connect<R: Runtime>(
    _app: AppHandle<R>,
    address: String,
    on_disconnect: Channel<()>,
) -> Result<Vec<Service>> {
    tracing::info!("Connecting to BLE device: {:?}", address);
    let handler = get_handler()?;
    let disconnct_handler = move || {
        on_disconnect
            .send(())
            .expect("failed to send disconnect event to the front-end");
    };
    let services = handler
        .connect(&address, Some(Box::new(disconnct_handler)))
        .await?;
    Ok(services)
}

#[command]
pub(crate) async fn disconnect<R: Runtime>(_app: AppHandle<R>) -> Result<()> {
    tracing::info!("Disconnecting from BLE device");
    let handler = get_handler()?;
    handler.disconnect().await?;
    Ok(())
}

#[command]
pub(crate) async fn connection_state<R: Runtime>(
    _app: AppHandle<R>,
    update: Channel<bool>,
) -> Result<()> {
    let handler = get_handler()?;
    let (tx, mut rx) = tokio::sync::mpsc::channel(1);
    handler.set_connection_update_channel(tx).await;
    update
        .send(handler.is_connected())
        .expect("failed to send connection state");
    async_runtime::spawn(async move {
        while let Some(connected) = rx.recv().await {
            update
                .send(connected)
                .expect("failed to send connection state to the front-end");
        }
    });
    Ok(())
}

#[command]
pub(crate) async fn scanning_state<R: Runtime>(
    _app: AppHandle<R>,
    update: Channel<bool>,
) -> Result<()> {
    let handler = get_handler()?;
    let (tx, mut rx) = tokio::sync::mpsc::channel(1);
    handler.set_scanning_update_channel(tx).await;
    update
        .send(handler.is_scanning().await)
        .expect("failed to send scanning state");
    async_runtime::spawn(async move {
        while let Some(scanning) = rx.recv().await {
            update
                .send(scanning)
                .expect("failed to send scanning state to the front-end");
        }
    });
    Ok(())
}

#[command]
pub(crate) async fn send<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
    service: Option<Uuid>,
    data: Vec<u8>,
    write_type: WriteType,
) -> Result<()> {
    let handler = get_handler()?;

    handler
        .send_data(
            characteristic,
            service,
            &data,
            write_type,
        )
        .await?;

    Ok(())
}

#[command]
pub(crate) async fn recv<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
    service: Option<Uuid>,
) -> Result<Vec<u8>> {
    let handler = get_handler()?;

    handler
        .recv_data(characteristic, service)
        .await
}

#[command]
pub(crate) async fn send_string<R: Runtime>(
    app: AppHandle<R>,
    characteristic: Uuid,
    service: Option<Uuid>,
    data: String,
    write_type: WriteType,
) -> Result<()> {
    send(
        app,
        characteristic,
        service,
        data.into_bytes(),
        write_type,
    )
    .await
}

#[command]
pub(crate) async fn recv_string<R: Runtime>(
    app: AppHandle<R>,
    characteristic: Uuid,
    service: Option<Uuid>,
) -> Result<String> {
    let data = recv(app, characteristic, service).await?;
    Ok(String::from_utf8_lossy(&data).into_owned())
}

async fn subscribe_channel(
    characteristic: Uuid,
    service: Option<Uuid>,
) -> Result<mpsc::Receiver<Vec<u8>>> {
    let handler = get_handler()?;

    // BLE notifications can arrive quickly. A capacity of 1 plus try_send()
    // makes transient bursts very likely to panic.
    let (tx, rx) = tokio::sync::mpsc::channel(512);

    handler
        .subscribe(
            characteristic,
            service,
            move |data| {
                info!("subscribe_channel: {:?}", data);

                if let Err(error) = tx.try_send(data.to_vec()) {
                    tracing::warn!(
                        "Failed to queue BLE notification: {error}"
                    );
                }
            },
        )
        .await?;

    Ok(rx)
}

#[command]
pub(crate) async fn subscribe<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
    service: Option<Uuid>,
    on_data: Channel<Vec<u8>>,
) -> Result<()> {
    let mut rx = subscribe_channel(characteristic, service).await?;

    async_runtime::spawn(async move {
        while let Some(data) = rx.recv().await {
            if let Err(error) = on_data.send(data) {
                tracing::warn!(
                    "Failed to send BLE notification to front-end: {error}"
                );
                break;
            }
        }
    });

    Ok(())
}

#[command]
pub(crate) async fn subscribe_string<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
    service: Option<Uuid>,
    on_data: Channel<String>,
) -> Result<()> {
    let mut rx = subscribe_channel(characteristic, service).await?;

    async_runtime::spawn(async move {
        while let Some(data) = rx.recv().await {
            match String::from_utf8(data) {
                Ok(data) => {
                    if let Err(error) = on_data.send(data) {
                        tracing::warn!(
                            "Failed to send BLE notification to front-end: {error}"
                        );
                        break;
                    }
                }

                Err(error) => {
                    tracing::warn!(
                        "Received invalid UTF-8 BLE notification: {error}"
                    );
                }
            }
        }
    });

    Ok(())
}

#[command]
pub(crate) async fn unsubscribe<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
    service: Option<Uuid>,
) -> Result<()> {
    let handler = get_handler()?;

    handler
        .unsubscribe(characteristic, service)
        .await
}

pub fn commands<R: Runtime>() -> impl Fn(tauri::ipc::Invoke<R>) -> bool {
    tauri::generate_handler![
        scan,
        stop_scan,
        connect,
        disconnect,
        connection_state,
        send,
        send_string,
        recv,
        recv_string,
        subscribe,
        subscribe_string,
        unsubscribe,
        scanning_state
    ]
}
