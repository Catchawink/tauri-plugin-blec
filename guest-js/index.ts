import { Channel, invoke } from '@tauri-apps/api/core'

export type BleDevice = {
  address: string;
  name: string;
  isConnected: boolean;
  services: string[];
  manufacturerData: Record<number, Uint8Array>;
};

/**
  * Scan for BLE devices
  * @param handler - A function that will be called with an array of devices found during the scan
  * @param timeout - The scan timeout in milliseconds
*/
export async function startScan(handler: (devices: BleDevice[]) => void, timeout: Number) {
  if (!timeout) {
    timeout = 10000;
  }
  let onDevices = new Channel<BleDevice[]>();
  onDevices.onmessage = handler;
  await invoke<BleDevice[]>('plugin:blec|scan', {
    timeout,
    onDevices
  })
}

/**
  * Stop scanning for BLE devices
*/
export async function stopScan() {
  console.log('stop scan')
  await invoke('plugin:blec|stop_scan')
}

/**
  * Register a handler to receive updates when the connection state changes
*/
export async function getConnectionUpdates(handler: (connected: boolean) => void) {
  let connection_chan = new Channel<boolean>()
  connection_chan.onmessage = handler
  await invoke('plugin:blec|connection_state', { update: connection_chan })
}

/**
 * Register a handler to receive updates when the scanning state changes
 */
export async function getScanningUpdates(handler: (scanning: boolean) => void) {
  let scanning_chan = new Channel<boolean>()
  scanning_chan.onmessage = handler
  await invoke('plugin:blec|scanning_state', { update: scanning_chan })
}

/**
  * Disconnect from the currently connected device
*/
export async function disconnect() {
  await invoke('plugin:blec|disconnect')
}

/**
  * Connect to a BLE device
  * @param address - The address of the device to connect to
  * @param onDisconnect - A function that will be called when the device disconnects
*/
export async function connect(address: string, onDisconnect: (() => void) | null) {
  console.log('connect', address)
  let disconnectChannel = new Channel()
  if (onDisconnect) {
    disconnectChannel.onmessage = onDisconnect
  }
  try {
    await invoke('plugin:blec|connect', {
      address: address,
      onDisconnect: disconnectChannel
    })
  } catch (e) {
    console.error(e)
  }
}

/**
 * Write a Uint8Array to a BLE characteristic
 * @param characteristic UUID of the characteristic to write to
 * @param data Data to write to the characteristic
 */
export async function send(characteristic: string, data: Uint8Array, writeType: 'withResponse' | 'withoutResponse' = 'withResponse') {
  await invoke('plugin:blec|send', {
    characteristic,
    data,
    writeType,
  })
}

/**
 * Write a string to a BLE characteristic
 * @param characteristic UUID of the characteristic to write to
 * @param data Data to write to the characteristic
 */
export async function sendString(characteristic: string, data: string, writeType: 'withResponse' | 'withoutResponse' = 'withResponse') {
  await invoke('plugin:blec|send_string', {
    characteristic,
    data,
    writeType,
  })
}

/**
 * Read bytes from a BLE characteristic
 * @param characteristic UUID of the characteristic to read from
 */
export async function read(characteristic: string): Promise<Uint8Array> {
  let res = await invoke<Uint8Array>('plugin:blec|recv', {
    characteristic
  })
  return res
}

/**
 * Read a string from a BLE characteristic
 * @param characteristic UUID of the characteristic to read from
 */
export async function readString(characteristic: string): Promise<string> {
  let res = await invoke<string>('plugin:blec|recv_string', {
    characteristic
  })
  return res
}

/**
 * Unsubscribe from a BLE characteristic
 * @param characteristic UUID of the characteristic to unsubscribe from
 */
export async function unsubscribe(characteristic: string) {
  await invoke('plugin:blec|unsubscribe', {
    characteristic
  })
}

/**
 * Subscribe to a BLE characteristic
 * @param characteristic UUID of the characteristic to subscribe to
 * @param handler Callback function that will be called with the data received for every notification
 */
export async function subscribe(characteristic: string, handler: (data: Uint8Array) => void) {
  let onData = new Channel<Uint8Array>()
  onData.onmessage = handler;
  await invoke('plugin:blec|subscribe', {
    characteristic,
    onData
  })
}

/**
 * Subscribe to a BLE characteristic. Converts the received data to a string
 * @param characteristic UUID of the characteristic to subscribe to
 * @param handler Callback function that will be called with the data received for every notification
 */
export async function subscribeString(characteristic: string, handler: (data: string) => void) {
  let onData = new Channel<string>()
  onData.onmessage = handler;
  await invoke('plugin:blec|subscribe_string', {
    characteristic,
    onData
  })
}
