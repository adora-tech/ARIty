package io.cloudonix.arity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import ch.loway.oss.ari4java.generated.Bridge;
import ch.loway.oss.ari4java.generated.LiveRecording;
import ch.loway.oss.ari4java.generated.Playback;
import ch.loway.oss.ari4java.generated.PlaybackFinished;
import ch.loway.oss.ari4java.generated.RecordingFinished;
import ch.loway.oss.ari4java.tools.AriCallback;
import ch.loway.oss.ari4java.tools.RestException;
import io.cloudonix.arity.errors.ErrorStream;

/**
 * The class handles all bridge operations
 * 
 * @author naamag
 *
 */
public class BridgeOperations {
	private ARIty arity;
	private final static Logger logger = Logger.getLogger(ARIty.class.getName());
	private String bridgeId;
	private String recordFormat = "ulaw";
	private int maxDurationSeconds;
	private int maxSilenceSeconds;
	private String ifExists;
	private boolean beep;
	private String terminateOn;
	private HashMap<String, LiveRecording> recordings = new HashMap<>();

	/**
	 * Constructor
	 * 
	 * @param arity instance of ARIty
	 */
	public BridgeOperations(ARIty arity) {
		this(arity, "", 0, 0, "overwrite", false, "#");
	}

	/**
	 * Constructor with more options
	 * 
	 * @param arity              instance of ARIty
	 * @param recordFormat       Format to encode audio in (to use the default
	 *                           'ulaw', use "")
	 * @param maxDurationSeconds Maximum duration of the recording, in seconds. 0
	 *                           for no limit
	 * @param maxSilenceSeconds  Maximum duration of silence, in seconds. 0 for no
	 *                           limit
	 * @param ifExists           Action to take if a recording with the same name
	 *                           already exists. Allowed values: fail, overwrite,
	 *                           append
	 * @param beep               true if need to play beep when recording begins,
	 *                           false otherwise
	 * @param terminateOn        DTMF input to terminate recording. Allowed values:
	 *                           none, any, *, #
	 */
	public BridgeOperations(ARIty arity, String recordFormat, int maxDurationSeconds, int maxSilenceSeconds,
			String ifExists, boolean beep, String terminateOn) {
		this.arity = arity;
		if (!Objects.equals(recordFormat, "") && Objects.nonNull(recordFormat))
			this.recordFormat = recordFormat;
		this.maxDurationSeconds = maxDurationSeconds;
		this.maxSilenceSeconds = maxSilenceSeconds;
		this.ifExists = ifExists;
		this.beep = beep;
		this.terminateOn = terminateOn;
		this.bridgeId = UUID.randomUUID().toString();
	}

	/**
	 * Create a new bridge
	 * 
	 * @return
	 */
	public CompletableFuture<Bridge> createBridge() {
		return Operation.toFuture(cb -> arity.getAri().bridges().create("mixing", bridgeId, "dialBridge", cb));
	}

	/**
	 * Shut down this bridge
	 * 
	 * @return
	 */
	public CompletableFuture<Void> destroyBridge() {
		return Operation.<Void>toFuture(cb -> arity.getAri().bridges().destroy(bridgeId, cb)).thenAccept(v -> {
			recordings.clear();
			logger.info("Bridge was destroyed successfully. Bridge id: " + bridgeId);
		});
	}

	/**
	 * add new channel to this bridge
	 * 
	 * @param channelId id of the channel to add the bridge
	 * @return
	 */
	public CompletableFuture<Void> addChannelToBridge(String channelId) {
		return Operation.<Void>toFuture(cb -> arity.getAri().bridges().addChannel(bridgeId, channelId, "member", cb));
	}

	/**
	 * remove channel from the bridge
	 * 
	 * @param channelId id of the channel to remove from the bridge
	 * @return
	 */
	public CompletableFuture<Void> removeChannelFromBridge(String channelId) {
		return Operation.<Void>toFuture(cb -> arity.getAri().bridges().removeChannel(bridgeId, channelId, cb));
	}

	/**
	 * Play media to the bridge
	 * 
	 * @param fileToPlay name of the file to be played
	 * @return
	 */
	public CompletableFuture<Playback> playMediaToBridge(String fileToPlay) {
		String playbackId = UUID.randomUUID().toString();
		return Operation.<Playback>toFuture(
				cb -> arity.getAri().bridges().play(bridgeId, "sound:" + fileToPlay, "en", 0, 0, playbackId, cb))
				.thenCompose(result -> {
					CompletableFuture<Playback> future = new CompletableFuture<Playback>();
					logger.fine("playing: " + fileToPlay);
					arity.addFutureEvent(PlaybackFinished.class, bridgeId, (pbf, se) -> {
						if (!(pbf.getPlayback().getId().equals(playbackId)))
							return;
						logger.fine("PlaybackFinished id is the same as playback id.  ID is: " + playbackId);
						future.complete(pbf.getPlayback());
						se.unregister();
					});
					logger.fine("Future event of playbackFinished was added");
					return future;
				});
	}

	/**
	 * play music on hold to the bridge
	 * 
	 * @param holdMusicFile file of the music to be played while holding ("" for
	 *                      default music)
	 * @return
	 */
	public CompletableFuture<Void> startMusicOnHold(String holdMusicFile) {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		String fileToPlay = (Objects.equals(holdMusicFile, "")) ? "pls-hold-while-try" : holdMusicFile;
		arity.getAri().bridges().startMoh(bridgeId, fileToPlay, new AriCallback<Void>() {
			@Override
			public void onSuccess(Void result) {
				logger.info("Playing music on hold to bridge: " + bridgeId);
				future.complete(result);
			}

			@Override
			public void onFailure(RestException e) {
				logger.info("Failed playing music on hold to bridge: " + e);
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	/**
	 * stop playing music on hold to the bridge
	 * 
	 * @return
	 */
	public CompletableFuture<Void> stopMusicOnHold() {
		CompletableFuture<Void> future = new CompletableFuture<Void>();
		arity.getAri().bridges().stopMoh(bridgeId, new AriCallback<Void>() {
			@Override
			public void onSuccess(Void result) {
				logger.info(" Stoped playing music on hold to bridge: " + bridgeId);
				future.complete(result);
			}

			@Override
			public void onFailure(RestException e) {
				logger.info("Failed playing music on hold to bridge: " + ErrorStream.fromThrowable(e));
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	/**
	 * record the mixed audio from all channels participating in this bridge.
	 * 
	 * @param recordingName name of the recording
	 * @return
	 */
	public CompletableFuture<LiveRecording> recordBridge(String recordingName) {
		CompletableFuture<LiveRecording> future = new CompletableFuture<LiveRecording>();
		long recordingStartTime = Instant.now().getEpochSecond();
		arity.getAri().bridges().record(bridgeId, recordingName, recordFormat, maxDurationSeconds, maxSilenceSeconds,
				ifExists, beep, terminateOn, new AriCallback<LiveRecording>() {
					@Override
					public void onSuccess(LiveRecording result) {
						logger.info("Strated Recording bridge with id: " + bridgeId + " and recording name is: "
								+ recordingName);
						arity.addFutureEvent(RecordingFinished.class, bridgeId, (record, se) -> {
							if (!Objects.equals(record.getRecording().getName(), recordingName))
								return;
							long recordingEndTime = Instant.now().getEpochSecond();
							logger.info("Finished recording: " + recordingName);
							record.getRecording().setDuration(
									Integer.valueOf(String.valueOf(Math.abs(recordingEndTime - recordingStartTime))));
							recordings.put(recordingName, record.getRecording());
							future.complete(record.getRecording());
							se.unregister();
						});
					}

					@Override
					public void onFailure(RestException e) {
						logger.info("Failed recording bridge: " + e);
						future.completeExceptionally(e);
					}
				});

		return future;
	}

	/**
	 * get bridge if exists
	 * 
	 * @return
	 */
	public CompletableFuture<Bridge> getBridge() {
		CompletableFuture<Bridge> future = new CompletableFuture<Bridge>();
		arity.getAri().bridges().get(bridgeId, new AriCallback<Bridge>() {
			@Override
			public void onSuccess(Bridge result) {
				logger.info("Found bridge with id: " + bridgeId);
				future.complete(result);
			}

			@Override
			public void onFailure(RestException e) {
				logger.info("Failed getting bridge: " + e);
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	/**
	 * get the id of the bridge
	 * 
	 * @return
	 */
	public String getBridgeId() {
		return bridgeId;
	}

	/**
	 * set the id of the bridge
	 * 
	 * @return
	 */
	public void setBridgeId(String bridgeId) {
		this.bridgeId = bridgeId;
	}

	/**
	 * get all recording for this bridge
	 * 
	 * @return
	 */
	public HashMap<String, LiveRecording> getRecordings() {
		return recordings;
	}

	/**
	 * get a specified recording by it's name if exists
	 * 
	 * @param recordName name of the recording
	 * @return the recording object if saved, null otherwise
	 */
	public LiveRecording getRecodingByName(String recordName) {
		return recordings.get(recordName);
	}

	/**
	 * get a specified recording
	 * 
	 * @param recordingName name of the recording we are looking for
	 * @return
	 */
	public LiveRecording getRecording(String recordingName) {
		for (Entry<String, LiveRecording> currRecording : recordings.entrySet()) {
			if (Objects.equals(recordingName, currRecording.getKey()))
				return currRecording.getValue();
		}
		logger.info("No recording with name: " + recordingName);
		return null;
	}

	public void setBeep(boolean beep) {
		this.beep = beep;
	}

	/**
	 * get list of channels that are connected to the bridge
	 * 
	 * @return
	 */
	public CompletableFuture<List<String>> getChannelsInBridge() {
		return getBridge().thenApply(bridge -> {
			if (Objects.nonNull(bridge))
				return bridge.getChannels();
			logger.warning("Bridge is null");
			return null;
		});
	}
}
