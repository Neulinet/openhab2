package org.openhab.binding.openwebnetvdes.handler;

import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.CHANNEL_OPEN_LOCK;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.CHANNEL_SWITCH_ON_OFF_CAMERA;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.OPEN_MSG_99_0;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.OPEN_MSG_MAC_ADDRESS_RESPONSE;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.OWN_RESPONSE_ACK;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.OWN_RESPONSE_MESSAGE_ACK;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.OWN_RESPONSE_MESSAGE_NACK;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.OWN_RESPONSE_NACK;
import static org.openhab.binding.openwebnetvdes.OpenWebNetVdesBindingConstants.OWN_WHERE;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.openwebnetvdes.configuration.BticinoDeviceConfiguration;
import org.openhab.binding.openwebnetvdes.configuration.Ip2WireBridgeConfiguration;
import org.openhab.binding.openwebnetvdes.devices.BticinoDevice;
import org.openhab.binding.openwebnetvdes.devices.DeviceFeatureType;
import org.openhab.binding.openwebnetvdes.internal.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Ip2WireBridgeHandler extends BaseBridgeHandler {
	private Logger logger = LoggerFactory.getLogger(Ip2WireBridgeHandler.class);
	
	public Ip2WireBridgeHandler(Bridge bridge) {
		super(bridge);
	}

	/** The refresh interval which is used to poll given IP interface */
	private long refreshInterval = 10000;
	ScheduledFuture<?> refreshJob;

	private List<BticinoDevice> devices = new ArrayList<BticinoDevice>();
	private Set<Integer> lastActiveDevices = new HashSet<Integer>();

	private  List<BticinoDeviceConfiguration> configurations = new ArrayList<BticinoDeviceConfiguration>();

	/** maximum queue size that we're allowing */
	private static final int MAX_COMMANDS = 50;
	private ArrayBlockingQueue<OwnRequest> commandQueue = new ArrayBlockingQueue<OwnRequest>(MAX_COMMANDS);

	private boolean connectionEstablished = false;

	private OwnRequest lastCommandId = null;

	private String ipAddress;
	private int port;
	private boolean exclusive;
	private int maxRequestsPerConnection;
	private int requestCount = 0;

	/**
	 * connection socket and reader/writer for execute method
	 */
	private Socket socket = null;

	private boolean previousOnline = false;

	private List<DeviceStatusListener> deviceStatusListeners = new CopyOnWriteArrayList<>();

	private ScheduledFuture<?> pollingJob;
	private Runnable pollingRunnable = new Runnable() {
		@Override
		public void run() {
			refreshData(); //TODO
		}
	};
	private ScheduledFuture<?> sendCommandJob;
	private long sendCommandInterval = 5000;
	private Runnable sendCommandRunnable = new Runnable() {
		@Override
		public void run() {
			sendCommands();
		}
	};

	@Override
	public void handleCommand(ChannelUID channelUID, Command command) {
		if (command instanceof RefreshType) {
			logger.debug("Refresh command received.");
			refreshData();
		} else
			logger.warn("No bridge commands defined.");
	}

	@Override
	public void dispose() {
		logger.debug("Handler disposed.");
		if (pollingJob != null && !pollingJob.isCancelled()) {
			pollingJob.cancel(true);
			pollingJob = null;
		}
		if (sendCommandJob != null && !sendCommandJob.isCancelled()) {
			sendCommandJob.cancel(true);
			sendCommandJob = null;
		}

		clearDeviceList();
		connectionEstablished = false;

		socketClose();
		super.dispose();
	}

	@Override
	public void initialize() {
		logger.debug("Initializing Bticino bridge handler.");

		Ip2WireBridgeConfiguration configuration = getConfigAs(Ip2WireBridgeConfiguration.class);
		port = configuration.port;
		ipAddress = configuration.ipAddress;
		refreshInterval = configuration.refreshInterval;
		exclusive = configuration.exclusive;
		//maxRequestsPerConnection = configuration.maxRequestsPerConnection;
		logger.debug("Bridge IP       {}.", ipAddress);
		logger.debug("Port            {}.", port);
		logger.debug("RefreshInterval {}.", refreshInterval);
		logger.debug("Exclusive mode  {}.", exclusive);
		logger.debug("Max Requests    {}.", maxRequestsPerConnection);

		startAutomaticRefresh();

		// workaround for issue #92: getHandler() returns NULL after
		// configuration update. :
		getThing().setHandler(this);
	}

	private synchronized void startAutomaticRefresh() {
		if (pollingJob == null || pollingJob.isCancelled()) {
			pollingJob = scheduler.scheduleAtFixedRate(pollingRunnable, 0, refreshInterval, TimeUnit.MILLISECONDS);
		}
		if (sendCommandJob == null || sendCommandJob.isCancelled()) {
			sendCommandJob = scheduler.scheduleAtFixedRate(sendCommandRunnable, 0, sendCommandInterval,
					TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Takes a command from the command queue and send it to
	 * {@link executeCommand} for execution.
	 * 
	 */
	private synchronized void sendCommands() {

		OwnRequest sendCommand = commandQueue.poll();
		if (sendCommand != null) {
			executeCommand(sendCommand);
		}
	}
	
	/**
	 * initiates read data from the Bticino bridge
	 */
	private synchronized void refreshData() {

		try {
			refreshBridgeInformation();
			if (connectionEstablished) {
				updateStatus(ThingStatus.ONLINE);
				previousOnline = true;
				for (BticinoDevice di : devices) {
					if (lastActiveDevices != null && lastActiveDevices.contains(di.getWhereAddress())) {
						for (DeviceStatusListener deviceStatusListener : deviceStatusListeners) {
							try {
								deviceStatusListener.onDeviceStateChanged(getThing().getUID(), di);
							} catch (Exception e) {
								logger.error("An exception occurred while calling the DeviceStatusListener", e);
							}
						}
					}
					// New device, not seen before, pass to Discovery
					else {
						for (DeviceStatusListener deviceStatusListener : deviceStatusListeners) {
							try {
								deviceStatusListener.onDeviceAdded(getThing(), di);
								di.setUpdated(true);
								deviceStatusListener.onDeviceStateChanged(getThing().getUID(), di);
							} catch (Exception e) {
								logger.error("An exception occurred while calling the DeviceStatusListener", e);
							}
							lastActiveDevices.add(di.getWhereAddress());
						}
					}
				}
			} else if (previousOnline)
				onConnectionLost();

		} catch (Exception e) {
			logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
		}
	}

	public void onConnectionLost() {
		logger.info("Bridge connection lost. Updating thing status to OFFLINE.");
		previousOnline = false;
		updateStatus(ThingStatus.OFFLINE);
	}

	public void onConnection() {
		logger.info("Bridge connected. Updating thing status to ONLINE.");
		updateStatus(ThingStatus.ONLINE);
	}

	public boolean registerDeviceStatusListener(DeviceStatusListener deviceStatusListener) {
		if (deviceStatusListener == null) {
			throw new NullPointerException("It's not allowed to pass a null deviceStatusListener.");
		}
		boolean result = deviceStatusListeners.add(deviceStatusListener);
		if (result) {
			// onUpdate();
		}
		return result;
	}

	public boolean unregisterDeviceStatusListener(DeviceStatusListener deviceStatusListener) {
		boolean result = deviceStatusListeners.remove(deviceStatusListener);
		if (result) {
			// onUpdate();
		}
		return result;
	}

	public void clearDeviceList() {
		lastActiveDevices = new HashSet<Integer>();
	}
	
	private void refreshBridgeInformation () {
		
		String commandString = OPEN_MSG_MAC_ADDRESS_RESPONSE;
		synchronized (Ip2WireBridgeHandler.class) {
			if (socketConnectOrReconnect()) {
				try {
			        byte[] writeBuffer = commandString.getBytes();
			        OutputStream out = socket.getOutputStream();
			        out.write(writeBuffer, 0, writeBuffer.length);
			        out.flush();

			        byte readBuffer[] = new byte[1024];
			        int readBytes = socket.getInputStream().read(readBuffer);
			        String response = new String(readBuffer, 0, readBytes);
					logger.info("Bridge MAC address : {}", response);
					//int askOrNask = processRawMessage(response);
					requestCount++;
					connectionEstablished = true;
					if (!exclusive) {
						socketClose();
					}
				
				} catch (IOException e) {
					logger.warn("Cannot write data from Btcino lan gateway while connecting to '{}'", ipAddress);
					logger.debug(Utils.getStackTrace(e));
					connectionEstablished = false;
					socketClose(); // reconnect on next execution
				}
				logger.debug("Command ({}) sent to the Bticino Bridge at IP: {}", commandString,
						ipAddress);
				logger.trace("Command, content: '{}'", commandString);
			} else {
				logger.debug("Command ({}) isn't sent to the Bticino Bridge at IP: {}", commandString,
						ipAddress);
			}
		}
	}

	/**
	 * Processes the raw TCP data read from the OWN protocol, returning the
	 * corresponding Message.
	 * 
	 * @param raw
	 *            the raw data line read from the MAX protocol
	 * @return message the @Message for the given raw data
	 */
	private int processRawMessage(String raw) {
		if (OWN_RESPONSE_MESSAGE_ACK.equals(raw))
			return OWN_RESPONSE_ACK;
		else if (OWN_RESPONSE_MESSAGE_NACK.equals(raw))
			return OWN_RESPONSE_NACK;
		else 
			logger.debug("Unknown message block: '{}'", raw);
		return -1;
	}

	private BticinoDevice getDevice(int ownWhereAddress, List<BticinoDevice> devices) {
		for (BticinoDevice device : devices) {
			if (ownWhereAddress == device.getWhereAddress()) {
				return device;
			}
		}
		return null;
	}

	/**
	 * Returns the MAX! Device decoded during the last refreshData
	 * 
	 * @param serialNumber
	 *            the serial number of the device as String
	 * @return device the {@link Device} information decoded in last refreshData
	 */

	public BticinoDevice getDevice(int ownWhereAddress) {
		return getDevice(ownWhereAddress, devices);
	}

	/**
	 * Takes the device command and puts it on the command queue to be processed
	 * by the MAX!Cube Lan Gateway. Note that if multiple commands for the same
	 * item-channel combination are send prior that they are processed by the
	 * Max!Cube, they will be removed from the queue as they would not be
	 * meaningful. This will improve the behavior when using sliders in the GUI.
	 * 
	 * @param SendCommand
	 *            the SendCommand containing the serial number of the device as
	 *            String the channelUID used to send the command and the the
	 *            command data
	 */
	public synchronized void queueCommand(OwnRequest sendCommand) {

		if (commandQueue.offer(sendCommand)) {
			if (lastCommandId != null) {
				if (lastCommandId.getKey().equals(sendCommand.getKey())) {
					if (commandQueue.remove(lastCommandId))
						logger.debug("Removed Command id {} ({}) from queue. Superceeded by {}", lastCommandId.getId(),
								lastCommandId.getKey(), sendCommand.getId());
				}
			}
			lastCommandId = sendCommand;
			logger.debug("Command queued id {} ({}).", sendCommand.getId(), sendCommand.getKey());

		} else {
			logger.debug("Command queued full dropping command id {} ({}).", sendCommand.getId(), sendCommand.getKey());
		}

	}

	/**
	 * Processes device command and sends it to the Bticino Lan Gateway.
	 * 
	 * @param OwnRequest
	 *            the SendCommand containing the serial number of the device as
	 *            String the channelUID used to send the command and the the
	 *            command data
	 */
	public int executeCommand(OwnRequest sendCommand) {
		int askOrNask = -1;
		int whereAddress = sendCommand.getWhereAddress();
		ChannelUID channelUID = sendCommand.getChannelUID();
		Command command = sendCommand.getCommand();

		BticinoDevice device = getDevice(whereAddress, devices);

		if (device == null) {
			logger.debug("Cannot send command to device with Where Address {}, device not listed.", whereAddress);
			return askOrNask;
		}
		
		String commandString = null;

		// Camera Switch On/Off
		if (channelUID.getId().equals(CHANNEL_SWITCH_ON_OFF_CAMERA)) {
			if (command instanceof OnOffType) {
				Set<DeviceFeatureType> deviceFeatures = device.getFeatures();
				Iterator<DeviceFeatureType> featureTypeIterator = deviceFeatures.iterator();
				while (featureTypeIterator.hasNext()) {
					DeviceFeatureType featureType = featureTypeIterator.next();
					if (featureType == DeviceFeatureType.RISER_CAMERA ||
							featureType == DeviceFeatureType.INDOOR_CAMERA) {
						commandString = OnOffType.ON.equals(command) ? featureType.ownFrameOn() : featureType.ownFrameOff();
						break;
					}
				}				
			}
		// Open Lock
		} else if (channelUID.getId().equals(CHANNEL_OPEN_LOCK)) {
			if (command instanceof OpenClosedType) {
				Set<DeviceFeatureType> deviceFeatures = device.getFeatures();
				Iterator<DeviceFeatureType> featureTypeIterator = deviceFeatures.iterator();
				while (featureTypeIterator.hasNext()) {
					DeviceFeatureType featureType = featureTypeIterator.next();
					if (featureType == DeviceFeatureType.RISER_DOOR_LOCK_ACTUATOR ||
							featureType == DeviceFeatureType.DOOR_LOCK_ACTUATOR) {
						commandString = OpenClosedType.OPEN.equals(command) ? featureType.ownFrameOpenLock() : null;
						break;
					}
				}				
			}
		}
		// Actual sending of the data to the Bticino Lan Gateway
		if (commandString != null) {
			commandString = prepareOwnFrame(commandString, whereAddress);
			synchronized (Ip2WireBridgeHandler.class) {
				if (socketConnectOrReconnect()) {
					try {
				        byte[] writeBuffer = commandString.getBytes();
				        OutputStream out = socket.getOutputStream();
				        out.write(writeBuffer, 0, writeBuffer.length);
				        out.flush();
	
				        byte readBuffer[] = new byte[1024];
				        int readBytes = socket.getInputStream().read(readBuffer);
				        String response = new String(readBuffer, 0, readBytes);
						
						askOrNask = processRawMessage(response);
						requestCount++;
						if (!exclusive) {
							socketClose();
						}
					
					} catch (IOException e) {
						logger.warn("Cannot write data from Btcino lan gateway while connecting to '{}'", ipAddress);
						logger.debug(Utils.getStackTrace(e));
						socketClose(); // reconnect on next execution
					}
					logger.debug("Command {} ({}) sent to the Bticino Bridge at IP: {}", sendCommand.getId(), sendCommand.getKey(),
							ipAddress);
					logger.trace("Command {} content: '{}'", sendCommand.getId(), commandString);
				} else {
					logger.debug("Command {} ({}) isn't sent to the Bticino Bridge at IP: {}", sendCommand.getId(), sendCommand.getKey(),
							ipAddress);
				}
			} 
		} else {
			logger.debug("Null Command not sent to {} (there is nothing to send)", ipAddress);
		}
		return askOrNask;
	}

	private boolean socketConnectOrReconnect() {
		boolean connected = true;
		try {
			if (socket == null) {
				connected = this.socketConnect();
				requestCount = 0;
			} else {
				if (maxRequestsPerConnection > 0 && requestCount >= maxRequestsPerConnection) {
					logger.debug("maxRequestsPerConnection reached, reconnecting.");
					socket.close();
					connected = this.socketConnect();
					requestCount = 0;
				}
			}
		} catch (UnknownHostException e) {
			logger.warn("Cannot establish connection with Btcino lan gateway while sending command to '{}'",
					ipAddress);
			logger.debug(Utils.getStackTrace(e));
			socketClose(); // reconnect on next execution
			connected = false;
		} catch (IOException e) {
			logger.warn("Cannot write data from Btcino lan gateway while connecting to '{}'", ipAddress);
			logger.debug(Utils.getStackTrace(e));
			socketClose(); // reconnect on next execution
			connected = false;
		}
		return connected;
	}
	
	private boolean socketConnect() throws UnknownHostException, IOException {
		socket = new Socket(ipAddress, port);
		logger.debug("Open new connection... to {} port {}", ipAddress, port);
		//reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		//writer = new OutputStreamWriter(socket.getOutputStream());
		
		byte readBuffer[] = new byte[1024];
        int readBytes = socket.getInputStream().read(readBuffer);
        String response = new String(readBuffer, 0, readBytes);
		
		int askOrNask = processRawMessage(response);
		if (!(askOrNask == OWN_RESPONSE_ACK)) {
			logger.debug("Acknowledgment message isn't received");
			socketClose();
			return false;
		}
			
		byte[] writeBuffer = OPEN_MSG_99_0.getBytes();
        OutputStream out = socket.getOutputStream();
        out.write(writeBuffer, 0, writeBuffer.length);
        out.flush();
       	
		return true;
	}

	private void socketClose() {
		try {
			socket.close();
		} catch (Exception e) {
		}
		socket = null;
	}

	protected String prepareOwnFrame(String rawFrame, Integer whereAddress) {
		String preparedFrame = rawFrame.replace(OWN_WHERE, whereAddress.toString());
		logger.debug("Prepare Own Frame WHERE={}. Raw Frame: {} , Prepared Frame : {}", whereAddress, rawFrame, preparedFrame);
		return preparedFrame;
	}
}

