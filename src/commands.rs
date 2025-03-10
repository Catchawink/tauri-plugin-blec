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
    data: Vec<u8>,
    write_type: WriteType,
) -> Result<()> {
    info!("Sending data: {data:?}");
    let handler = get_handler()?;
    handler.send_data(characteristic, &data, write_type).await?;
    Ok(())
}

#[command]
pub(crate) async fn recv<R: Runtime>(_app: AppHandle<R>, characteristic: Uuid) -> Result<Vec<u8>> {
    let handler = get_handler()?;
    let data = handler.recv_data(characteristic).await?;
    Ok(data)
}

#[command]
pub(crate) async fn send_string<R: Runtime>(
    app: AppHandle<R>,
    characteristic: Uuid,
    data: String,
    write_type: WriteType,
) -> Result<()> {
    let data = data.as_bytes().to_vec();
    send(app, characteristic, data, write_type).await
}

#[command]
pub(crate) async fn recv_string<R: Runtime>(
    app: AppHandle<R>,
    characteristic: Uuid,
) -> Result<String> {
    let data = recv(app, characteristic).await?;
    Ok(String::from_utf8(data).expect("failed to convert data to string"))
}

async fn subscribe_channel(characteristic: Uuid) -> Result<mpsc::Receiver<Vec<u8>>> {
    let handler = get_handler()?;
    let (tx, rx) = tokio::sync::mpsc::channel(1);
    handler
        .subscribe(characteristic, move |data| {
            info!("subscribe_channel: {:?}", data);
            tx.try_send(data.to_vec())
                .expect("failed to send data to the channel");
        })
        .await?;
    Ok(rx)
}
#[command]
pub(crate) async fn subscribe<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
    on_data: Channel<Vec<u8>>,
) -> Result<()> {
    let mut rx = subscribe_channel(characteristic).await?;
    async_runtime::spawn(async move {
        while let Some(data) = rx.recv().await {
            on_data
                .send(data)
                .expect("failed to send data to the front-end");
        }
    });
    Ok(())
}

#[command]
pub(crate) async fn subscribe_string<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
    on_data: Channel<String>,
) -> Result<()> {
    let mut rx = subscribe_channel(characteristic).await?;
    async_runtime::spawn(async move {
        while let Some(data) = rx.recv().await {
            info!("subscribe_string: {:?}", data);
            let data = String::from_utf8(data).expect("failed to convert data to string");
            on_data
                .send(data)
                .expect("failed to send data to the front-end");
        }
    });
    Ok(())
}

#[command]
pub(crate) async fn unsubscribe<R: Runtime>(
    _app: AppHandle<R>,
    characteristic: Uuid,
) -> Result<()> {
    let handler = get_handler()?;
    handler.unsubscribe(characteristic).await?;
    Ok(())
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
